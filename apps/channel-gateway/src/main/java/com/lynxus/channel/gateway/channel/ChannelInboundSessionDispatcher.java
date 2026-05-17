package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundTurnMessageStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundTurnResult;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelSenderType;
import com.lynxus.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnMessage;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
import com.lynxus.contracts.session.SessionContracts.SessionMessageRole;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSender;
import com.lynxus.contracts.session.SessionContracts.SessionMessageSenderType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ChannelInboundSessionDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ChannelInboundSessionDispatcher.class);

    private final ChannelAdminRepository repository;
    private final ChannelSessionRuntimeClient sessionRuntimeClient;
    private final ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient;
    private final List<ChannelInboundSessionDispatchObserver> observers;

    @Autowired
    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelSessionRuntimeClient sessionRuntimeClient,
        ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient,
        List<ChannelInboundSessionDispatchObserver> observers
    ) {
        this.repository = repository;
        this.sessionRuntimeClient = sessionRuntimeClient;
        this.bindingSnapshotRefreshHintClient = bindingSnapshotRefreshHintClient;
        this.observers = observers == null || observers.isEmpty() ? List.of() : List.copyOf(observers);
    }

    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelSessionRuntimeClient sessionRuntimeClient
    ) {
        this(repository, sessionRuntimeClient, null, List.of());
    }

    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelSessionRuntimeClient sessionRuntimeClient,
        ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient
    ) {
        this(repository, sessionRuntimeClient, bindingSnapshotRefreshHintClient, List.of());
    }

    public NormalizedChannelInboundTurnResult dispatch(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult
    ) {
        if (turn == null || ingestResult == null) {
            throw new IllegalArgumentException("normalized turn and ingest result are required");
        }
        ChannelInboundTurnAudit audit = ingestResult.turn();
        try {
            DispatchPlan plan = claimDispatchableMessages(audit);
            if (plan.messagesToDispatch().isEmpty()) {
                NoDispatchFinalization finalization = finalizeWithoutDispatch(audit, plan.allMessages(), ingestResult.duplicateDedupKey());
                NormalizedChannelInboundTurnResult result = resultFromAudit(
                    audit.turnId(),
                    finalization.sessionId(),
                    finalization.status(),
                    ingestResult.duplicateDedupKey(),
                    acceptedSessionMessageIds(plan.allMessages()),
                    duplicateExternalMessageIds(plan.allMessages()),
                    finalization.status() == ChannelInboundTurnStatus.DUPLICATE ? "duplicate messages ignored" : null
                );
                notifyDispatchSucceeded(turn, ingestResult, result);
                return result;
            }

            ChannelInboundSessionTurnResponse response = sessionRuntimeClient.dispatchInboundTurn(toApiRequest(
                audit,
                ingestResult.binding(),
                plan.messagesToDispatch()
            ));
            NormalizedChannelInboundTurnResult result = markAccepted(audit, ingestResult, plan, response);
            notifyDispatchSucceeded(turn, ingestResult, result);
            return result;
        } catch (ChannelInboundSessionRejectedException error) {
            NormalizedChannelInboundTurnResult result = markRejected(audit, error.getMessage(), ingestResult.duplicateDedupKey());
            notifyDispatchFailed(turn, ingestResult, error);
            return result;
        } catch (RuntimeException error) {
            NormalizedChannelInboundTurnResult result = markFailed(audit, error.getMessage(), ingestResult.duplicateDedupKey());
            notifyDispatchFailed(turn, ingestResult, error);
            log.warn(
                "failed to dispatch channel inbound turn to session runtime: channelProfileId={}, externalConversationId={}, turnId={}, dedupKey={}",
                audit.channelProfileId(),
                audit.externalConversationId(),
                audit.turnId(),
                audit.dedupKey(),
                error
            );
            return result;
        }
    }

    private DispatchPlan claimDispatchableMessages(ChannelInboundTurnAudit audit) {
        return repository.transactionResult(transactionalRepository -> {
            List<ChannelInboundTurnMessageAudit> messages = transactionalRepository.listInboundTurnMessages(audit.turnId());
            Instant now = Instant.now();
            for (ChannelInboundTurnMessageAudit message : messages) {
                if (message.status() != ChannelInboundTurnMessageStatus.RECEIVED) {
                    continue;
                }
                boolean claimed = transactionalRepository.claimInboundMessageDedupe(
                    message.channelProfileId(),
                    message.externalConversationId(),
                    message.externalMessageId(),
                    message.turnId(),
                    message.requestIndex(),
                    now
                );
                ChannelInboundMessageDedupeAudit dedupe = transactionalRepository.findInboundMessageDedupe(
                    message.channelProfileId(),
                    message.externalConversationId(),
                    message.externalMessageId()
                ).orElseThrow();
                boolean ownsClaim = claimed
                    || (message.turnId().equals(dedupe.firstTurnId()) && message.requestIndex() == dedupe.firstRequestIndex());
                if (!ownsClaim) {
                    transactionalRepository.updateInboundTurnMessageStatus(
                        message.turnId(),
                        message.requestIndex(),
                        ChannelInboundTurnMessageStatus.DUPLICATE,
                        dedupe.sessionMessageId(),
                        dedupe.firstTurnId(),
                        now
                    );
                }
            }
            List<ChannelInboundTurnMessageAudit> refreshed = transactionalRepository.listInboundTurnMessages(audit.turnId());
            List<ChannelInboundTurnMessageAudit> dispatchable = refreshed.stream()
                .filter(message -> message.status() == ChannelInboundTurnMessageStatus.RECEIVED)
                .toList();
            return new DispatchPlan(refreshed, dispatchable);
        });
    }

    private NoDispatchFinalization finalizeWithoutDispatch(
        ChannelInboundTurnAudit audit,
        List<ChannelInboundTurnMessageAudit> messages,
        boolean duplicateDedupKey
    ) {
        ChannelInboundTurnStatus status = inferFinalStatus(messages);
        if (!duplicateDedupKey && status == ChannelInboundTurnStatus.RECEIVED) {
            status = ChannelInboundTurnStatus.DUPLICATE;
        }
        String sessionId = firstKnownSessionId(messages, audit.sessionId());
        repository.updateInboundTurnStatus(audit.turnId(), status, sessionId, Instant.now());
        return new NoDispatchFinalization(status, sessionId);
    }

    private NormalizedChannelInboundTurnResult markAccepted(
        ChannelInboundTurnAudit audit,
        ChannelInboundTurnIngestResult ingestResult,
        DispatchPlan plan,
        ChannelInboundSessionTurnResponse response
    ) {
        Map<Integer, ChannelInboundTurnMessageAudit> dispatchedByApiIndex = new LinkedHashMap<>();
        for (int index = 0; index < plan.messagesToDispatch().size(); index += 1) {
            dispatchedByApiIndex.put(index, plan.messagesToDispatch().get(index));
        }
        Map<Integer, AcceptedSessionMessageAllocation> allocationsByIndex = response.acceptedMessageAllocations().stream()
            .collect(Collectors.toMap(
                AcceptedSessionMessageAllocation::requestIndex,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
            ));
        Set<String> apiDuplicateExternalIds = new HashSet<>(response.duplicateExternalMessageIds());
        Instant now = Instant.now();
        for (Map.Entry<Integer, ChannelInboundTurnMessageAudit> entry : dispatchedByApiIndex.entrySet()) {
            ChannelInboundTurnMessageAudit message = entry.getValue();
            AcceptedSessionMessageAllocation allocation = allocationsByIndex.get(entry.getKey());
            if (allocation != null) {
                repository.updateInboundTurnMessageStatus(
                    message.turnId(),
                    message.requestIndex(),
                    ChannelInboundTurnMessageStatus.ACCEPTED,
                    allocation.messageId(),
                    null,
                    now
                );
                repository.updateInboundMessageDedupeSession(
                    message.channelProfileId(),
                    message.externalConversationId(),
                    message.externalMessageId(),
                    response.sessionId(),
                    allocation.messageId()
                );
            } else if (apiDuplicateExternalIds.contains(message.externalMessageId())) {
                repository.updateInboundTurnMessageStatus(
                    message.turnId(),
                    message.requestIndex(),
                    ChannelInboundTurnMessageStatus.DUPLICATE,
                    null,
                    message.turnId(),
                    now
                );
            } else {
                repository.updateInboundTurnMessageStatus(
                    message.turnId(),
                    message.requestIndex(),
                    ChannelInboundTurnMessageStatus.REJECTED,
                    null,
                    null,
                    now
                );
            }
        }

        List<ChannelInboundTurnMessageAudit> refreshed = repository.listInboundTurnMessages(audit.turnId());
        ChannelInboundTurnStatus status = inferFinalStatus(refreshed);
        repository.updateInboundTurnStatus(audit.turnId(), status, response.sessionId(), now);
        ChannelConversationBinding updatedBinding = attachSession(ingestResult.binding(), response.sessionId());
        if (!Objects.equals(updatedBinding.sessionId(), ingestResult.binding().sessionId())) {
            sendBindingSnapshotRefreshHint(updatedBinding);
        }
        return resultFromAudit(
            audit.turnId(),
            response.sessionId(),
            status,
            ingestResult.duplicateDedupKey(),
            acceptedSessionMessageIds(refreshed),
            duplicateExternalMessageIds(refreshed),
            response.reason()
        );
    }

    private NormalizedChannelInboundTurnResult markRejected(
        ChannelInboundTurnAudit audit,
        String reason,
        boolean duplicateDedupKey
    ) {
        Instant now = Instant.now();
        for (ChannelInboundTurnMessageAudit message : repository.listInboundTurnMessages(audit.turnId())) {
            if (message.status() == ChannelInboundTurnMessageStatus.RECEIVED) {
                repository.updateInboundTurnMessageStatus(
                    message.turnId(),
                    message.requestIndex(),
                    ChannelInboundTurnMessageStatus.REJECTED,
                    null,
                    null,
                    now
                );
            }
        }
        repository.updateInboundTurnStatus(audit.turnId(), ChannelInboundTurnStatus.REJECTED, audit.sessionId(), now);
        return resultFromAudit(
            audit.turnId(),
            audit.sessionId(),
            ChannelInboundTurnStatus.REJECTED,
            duplicateDedupKey,
            acceptedSessionMessageIds(repository.listInboundTurnMessages(audit.turnId())),
            duplicateExternalMessageIds(repository.listInboundTurnMessages(audit.turnId())),
            reason
        );
    }

    private NormalizedChannelInboundTurnResult markFailed(
        ChannelInboundTurnAudit audit,
        String reason,
        boolean duplicateDedupKey
    ) {
        repository.updateInboundTurnStatus(audit.turnId(), ChannelInboundTurnStatus.FAILED, audit.sessionId(), Instant.now());
        List<ChannelInboundTurnMessageAudit> messages = repository.listInboundTurnMessages(audit.turnId());
        return resultFromAudit(
            audit.turnId(),
            audit.sessionId(),
            ChannelInboundTurnStatus.FAILED,
            duplicateDedupKey,
            acceptedSessionMessageIds(messages),
            duplicateExternalMessageIds(messages),
            reason
        );
    }

    private ChannelInboundSessionTurnRequest toApiRequest(
        ChannelInboundTurnAudit audit,
        ChannelConversationBinding binding,
        List<ChannelInboundTurnMessageAudit> messages
    ) {
        List<ChannelInboundSessionTurnMessage> apiMessages = new ArrayList<>();
        for (ChannelInboundTurnMessageAudit message : messages) {
            apiMessages.add(new ChannelInboundSessionTurnMessage(
                message.externalEventId(),
                message.externalMessageId(),
                message.occurredAt(),
                SessionMessageRole.valueOf(message.role().name()),
                toSessionSender(message.sender()),
                toSessionMessageInput(audit, message),
                messageMetadata(audit, message)
            ));
        }
        return new ChannelInboundSessionTurnRequest(
            audit.channelProfileId(),
            audit.externalConversationId(),
            audit.dedupKey(),
            binding.assistantId(),
            binding.customerId(),
            binding.sessionId(),
            apiMessages,
            audit.metadata()
        );
    }

    private static SessionMessageSender toSessionSender(NormalizedChannelMessageSender sender) {
        if (sender == null) {
            throw new IllegalArgumentException("channelInbound.message.sender is required");
        }
        NormalizedChannelSenderType senderType = sender.senderType();
        if (senderType == null) {
            throw new IllegalArgumentException("channelInbound.message.sender.senderType is required");
        }
        return new SessionMessageSender(
            SessionMessageSenderType.valueOf(senderType.name()),
            sender.senderId(),
            requireText(sender.senderName(), "channelInbound.message.sender.senderName")
        );
    }

    private static SessionMessageInput toSessionMessageInput(
        ChannelInboundTurnAudit audit,
        ChannelInboundTurnMessageAudit message
    ) {
        List<Object> blocks = new ArrayList<>();
        String type = message.messageType() == null ? "TEXT" : message.messageType().toUpperCase(Locale.ROOT);
        if ("RICH_TEXT".equals(type) && hasText(message.text())) {
            blocks.add(Map.of("type", "RICH_TEXT", "format", "MARKDOWN", "content", message.text()));
        } else if (hasText(message.text())) {
            blocks.add(Map.of("type", "TEXT", "text", message.text()));
        }
        for (NormalizedChannelAttachment attachment : message.attachments()) {
            blocks.add(toAttachmentBlock(attachment));
        }
        return new SessionMessageInput(blocks, messageMetadata(audit, message));
    }

    private static Map<String, Object> toAttachmentBlock(NormalizedChannelAttachment attachment) {
        if (attachment != null && isImageMimeType(attachment.mimeType())) {
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("type", "IMAGE");
            image.put("url", attachment.url());
            image.put("mimeType", attachment.mimeType());
            image.put("alt", attachment.fileName());
            return Map.copyOf(image);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (attachment != null) {
            putIfPresent(data, "externalAttachmentId", attachment.externalAttachmentId());
            putIfPresent(data, "externalFileId", attachment.externalFileId());
            putIfPresent(data, "fileName", attachment.fileName());
            putIfPresent(data, "mimeType", attachment.mimeType());
            putIfPresent(data, "url", attachment.url());
            if (attachment.sizeBytes() != null) {
                data.put("sizeBytes", attachment.sizeBytes());
            }
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "CARD");
        card.put("cardType", "FILE_ATTACHMENT");
        card.put("version", "1");
        card.put("data", Map.copyOf(data));
        if (attachment != null && hasText(attachment.url())) {
            card.put("actions", List.of(Map.of("actionType", "LINK", "label", "Open", "url", attachment.url())));
        } else {
            card.put("actions", List.of());
        }
        return Map.copyOf(card);
    }

    private static Map<String, Object> messageMetadata(ChannelInboundTurnAudit audit, ChannelInboundTurnMessageAudit message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerType", audit.providerType());
        metadata.put("channelProfileId", audit.channelProfileId());
        metadata.put("externalConversationId", audit.externalConversationId());
        metadata.put("externalMessageId", message.externalMessageId());
        metadata.put("dedupKey", audit.dedupKey());
        metadata.put("requestIndex", message.requestIndex());
        putIfPresent(metadata, "externalEventId", message.externalEventId());
        if (message.metadata() != null) {
            metadata.putAll(message.metadata());
        }
        return Map.copyOf(metadata);
    }

    private ChannelConversationBinding attachSession(ChannelConversationBinding binding, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.equals(binding.sessionId())) {
            return binding;
        }
        ChannelConversationBinding updated = new ChannelConversationBinding(
            binding.id(),
            binding.channelProfileId(),
            binding.externalConversationId(),
            binding.externalUserId(),
            binding.assistantId(),
            binding.customerId(),
            sessionId,
            binding.status(),
            binding.metadata(),
            binding.createdAt(),
            Instant.now()
        );
        repository.saveBinding(updated);
        return updated;
    }

    private void sendBindingSnapshotRefreshHint(ChannelConversationBinding binding) {
        if (bindingSnapshotRefreshHintClient == null) {
            return;
        }
        try {
            bindingSnapshotRefreshHintClient.bindingSessionAttached(binding);
        } catch (RuntimeException error) {
            log.warn(
                "failed to send channel binding snapshot refresh hint after attachSession: bindingId={}, sessionId={}",
                binding.id(),
                binding.sessionId(),
                error
            );
        }
    }

    private void notifyDispatchSucceeded(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult,
        NormalizedChannelInboundTurnResult dispatchResult
    ) {
        for (ChannelInboundSessionDispatchObserver observer : observers) {
            try {
                observer.afterDispatchSucceeded(turn, ingestResult, dispatchResult);
            } catch (RuntimeException error) {
                log.warn("channel inbound turn dispatch success observer failed: dedupKey={}", turn.dedupKey(), error);
            }
        }
    }

    private void notifyDispatchFailed(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult,
        RuntimeException error
    ) {
        for (ChannelInboundSessionDispatchObserver observer : observers) {
            try {
                observer.afterDispatchFailed(turn, ingestResult, error);
            } catch (RuntimeException observerError) {
                log.warn("channel inbound turn dispatch failure observer failed: dedupKey={}", turn.dedupKey(), observerError);
            }
        }
    }

    private static NormalizedChannelInboundTurnResult resultFromAudit(
        String turnId,
        String sessionId,
        ChannelInboundTurnStatus status,
        boolean duplicate,
        List<String> acceptedMessageIds,
        List<String> duplicateExternalMessageIds,
        String reason
    ) {
        return new NormalizedChannelInboundTurnResult(
            turnId,
            sessionId,
            status,
            duplicate,
            acceptedMessageIds,
            duplicateExternalMessageIds,
            reason
        );
    }

    private static ChannelInboundTurnStatus inferFinalStatus(List<ChannelInboundTurnMessageAudit> messages) {
        long accepted = messages.stream().filter(message -> message.status() == ChannelInboundTurnMessageStatus.ACCEPTED).count();
        long duplicate = messages.stream().filter(message -> message.status() == ChannelInboundTurnMessageStatus.DUPLICATE).count();
        long rejected = messages.stream().filter(message -> message.status() == ChannelInboundTurnMessageStatus.REJECTED).count();
        long received = messages.stream().filter(message -> message.status() == ChannelInboundTurnMessageStatus.RECEIVED).count();
        if (received > 0) {
            return ChannelInboundTurnStatus.RECEIVED;
        }
        if (accepted > 0 && duplicate > 0) {
            return ChannelInboundTurnStatus.PARTIALLY_DISPATCHED;
        }
        if (accepted > 0 && rejected == 0) {
            return ChannelInboundTurnStatus.DISPATCHED;
        }
        if (duplicate > 0 && accepted == 0 && rejected == 0) {
            return ChannelInboundTurnStatus.DUPLICATE;
        }
        if (rejected > 0) {
            return ChannelInboundTurnStatus.REJECTED;
        }
        return ChannelInboundTurnStatus.DUPLICATE;
    }

    private static List<String> acceptedSessionMessageIds(List<ChannelInboundTurnMessageAudit> messages) {
        return messages.stream()
            .filter(message -> message.status() == ChannelInboundTurnMessageStatus.ACCEPTED)
            .map(ChannelInboundTurnMessageAudit::sessionMessageId)
            .filter(ChannelInboundSessionDispatcher::hasText)
            .toList();
    }

    private static List<String> duplicateExternalMessageIds(List<ChannelInboundTurnMessageAudit> messages) {
        return messages.stream()
            .filter(message -> message.status() == ChannelInboundTurnMessageStatus.DUPLICATE)
            .map(ChannelInboundTurnMessageAudit::externalMessageId)
            .toList();
    }

    private String firstKnownSessionId(List<ChannelInboundTurnMessageAudit> messages, String fallback) {
        if (hasText(fallback)) {
            return fallback;
        }
        for (ChannelInboundTurnMessageAudit message : messages) {
            String sessionId = repository.findInboundMessageDedupe(
                    message.channelProfileId(),
                    message.externalConversationId(),
                    message.externalMessageId()
                )
                .map(ChannelInboundMessageDedupeAudit::sessionId)
                .filter(ChannelInboundSessionDispatcher::hasText)
                .orElse(null);
            if (sessionId != null) {
                return sessionId;
            }
        }
        return null;
    }

    private static boolean isImageMimeType(String mimeType) {
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            map.put(key, value);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requireText(String value, String field) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private record DispatchPlan(
        List<ChannelInboundTurnMessageAudit> allMessages,
        List<ChannelInboundTurnMessageAudit> messagesToDispatch
    ) {
    }

    private record NoDispatchFinalization(
        ChannelInboundTurnStatus status,
        String sessionId
    ) {
    }
}
