package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnMessageStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurnResult;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelSenderType;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionAllocation;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionMessage;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionRequest;
import com.agentyard.contracts.channel.ChannelContracts.ChannelReceptionResponse;
import com.agentyard.contracts.runtime.RuntimeContracts.MessageContent;
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
    private final ChannelReceptionClient receptionClient;
    private final ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient;
    private final List<ChannelInboundSessionDispatchObserver> observers;

    @Autowired
    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelReceptionClient receptionClient,
        ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient,
        List<ChannelInboundSessionDispatchObserver> observers
    ) {
        this.repository = repository;
        this.receptionClient = receptionClient;
        this.bindingSnapshotRefreshHintClient = bindingSnapshotRefreshHintClient;
        this.observers = observers == null || observers.isEmpty() ? List.of() : List.copyOf(observers);
    }

    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelReceptionClient receptionClient
    ) {
        this(repository, receptionClient, null, List.of());
    }

    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelReceptionClient receptionClient,
        ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient
    ) {
        this(repository, receptionClient, bindingSnapshotRefreshHintClient, List.of());
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

            ChannelReceptionResponse response = receptionClient.dispatchInboundTurn(toApiRequest(
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
            log.warn(
                "Agent reception rejected channel inbound turn: channelProfileId={}, externalConversationId={}, turnId={}, dedupKey={}, reason={}",
                audit.channelProfileId(),
                audit.externalConversationId(),
                audit.turnId(),
                audit.dedupKey(),
                error.getMessage()
            );
            return result;
        } catch (RuntimeException error) {
            NormalizedChannelInboundTurnResult result = markFailed(audit, error.getMessage(), ingestResult.duplicateDedupKey());
            notifyDispatchFailed(turn, ingestResult, error);
            log.warn(
                "failed to dispatch channel inbound turn to Agent reception: channelProfileId={}, externalConversationId={}, turnId={}, dedupKey={}",
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
        ChannelReceptionResponse response
    ) {
        Map<Integer, ChannelInboundTurnMessageAudit> dispatchedByApiIndex = new LinkedHashMap<>();
        for (int index = 0; index < plan.messagesToDispatch().size(); index += 1) {
            dispatchedByApiIndex.put(index, plan.messagesToDispatch().get(index));
        }
        Map<Integer, ChannelReceptionAllocation> allocationsByIndex = response.acceptedMessageAllocations().stream()
            .collect(Collectors.toMap(
                ChannelReceptionAllocation::requestIndex,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
            ));
        Set<String> apiDuplicateExternalIds = new HashSet<>(response.duplicateExternalMessageIds());
        Instant now = Instant.now();
        for (Map.Entry<Integer, ChannelInboundTurnMessageAudit> entry : dispatchedByApiIndex.entrySet()) {
            ChannelInboundTurnMessageAudit message = entry.getValue();
            ChannelReceptionAllocation allocation = allocationsByIndex.get(entry.getKey());
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
                    allocation.sessionId(),
                    allocation.messageId()
                );
                sendBindingSnapshotRefreshHint(attachReception(ingestResult.binding(), message, allocation));
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

    private ChannelReceptionRequest toApiRequest(
        ChannelInboundTurnAudit audit,
        ChannelConversationBinding binding,
        List<ChannelInboundTurnMessageAudit> messages
    ) {
        List<ChannelReceptionMessage> apiMessages = new ArrayList<>();
        for (ChannelInboundTurnMessageAudit message : messages) {
            apiMessages.add(new ChannelReceptionMessage(
                message.externalMessageId(),
                requireText(message.sender().senderId(), "channel sender identity"),
                requireText(message.sender().senderName(), "channel sender name"),
                toMessageContent(audit, message)
            ));
        }
        return new ChannelReceptionRequest(
            audit.channelProfileId(),
            audit.externalConversationId(),
            audit.dedupKey(),
            binding.entryId(),
            apiMessages,
            audit.metadata()
        );
    }

    private static MessageContent toMessageContent(
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
        return new MessageContent(blocks, messageMetadata(audit, message));
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

    private ChannelConversationBinding attachReception(ChannelConversationBinding binding,
        ChannelInboundTurnMessageAudit message, ChannelReceptionAllocation allocation) {
        String externalUserId = requireText(message.sender().senderId(), "channel sender identity");
        ChannelConversationBinding existing = repository.findBindingByProfileAndExternalConversation(
            binding.channelProfileId(), binding.externalConversationId(), externalUserId).orElse(null);
        ChannelConversationBinding updated = new ChannelConversationBinding(
            existing == null ? "channel-binding-" + java.util.UUID.randomUUID().toString().substring(0, 8) : existing.id(),
            binding.channelProfileId(),
            binding.externalConversationId(),
            externalUserId,
            binding.entryId(),
            allocation.participantId(),
            allocation.sessionId(),
            binding.status(),
            binding.metadata(),
            existing == null ? Instant.now() : existing.createdAt(),
            Instant.now()
        );
        repository.saveBindingForConversation(updated);
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
