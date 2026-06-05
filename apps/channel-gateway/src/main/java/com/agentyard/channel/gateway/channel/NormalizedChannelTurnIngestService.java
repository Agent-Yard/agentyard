package com.agentyard.channel.gateway.channel;

import com.agentyard.channel.gateway.extension.ExtensionRegistrationService;
import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnMessageStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelSenderType;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTurnMessage;
import com.agentyard.extension.sdk.protocol.DescriptorType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NormalizedChannelTurnIngestService {
    private final ChannelAdminRepository repository;
    private final ExtensionRegistrationService registrationService;

    public NormalizedChannelTurnIngestService(
        ChannelAdminRepository repository,
        ExtensionRegistrationService registrationService
    ) {
        this.repository = repository;
        this.registrationService = registrationService;
    }

    public ChannelInboundTurnIngestResult ingest(
        NormalizedChannelInboundTurn turn,
        NormalizedChannelEventHeaders headers
    ) {
        if (turn == null) {
            throw new IllegalArgumentException("normalizedTurn is required");
        }
        validateHeaders(turn, headers);
        validateTurn(turn);
        ChannelGatewayProfile profile = requireProfile(turn);
        ChannelAssistantBinding assistantBinding = requireAssistantBinding(profile);

        ChannelInboundTurnAudit existing = repository.findInboundTurnByDedupKey(turn.dedupKey()).orElse(null);
        if (existing != null) {
            requireSameTurnSurface(existing, turn);
        }

        Instant now = Instant.now();
        ChannelInboundTurnAudit audit = existing == null
            ? new ChannelInboundTurnAudit(
                nextId("channel-inbound-turn"),
                profile.id(),
                profile.providerType(),
                turn.dedupKey(),
                turn.externalConversationId(),
                turn.externalUserId(),
                turn.normalizedPayload(),
                turn.rawPayload(),
                traceContextMap(turn),
                turn.metadata(),
                ChannelInboundTurnStatus.RECEIVED,
                null,
                now,
                now
            )
            : existing;

        ChannelConversationBinding binding = repository.transactionResult(transactionalRepository -> {
            boolean insertedTurn = existing == null && transactionalRepository.saveInboundTurnIfAbsent(audit);
            ChannelInboundTurnAudit persisted = insertedTurn || existing != null
                ? audit
                : transactionalRepository.findInboundTurnByDedupKey(turn.dedupKey())
                    .orElseThrow(() -> new IllegalStateException("normalized turn duplicate disappeared: " + turn.dedupKey()));
            if (!persisted.channelProfileId().equals(profile.id())) {
                throw new IllegalArgumentException("normalized turn dedupKey already belongs to a different turn surface");
            }
            if (insertedTurn) {
                for (int index = 0; index < turn.messages().size(); index += 1) {
                    transactionalRepository.saveInboundTurnMessageIfAbsent(toAuditMessage(persisted, turn, index, now));
                }
            } else if (existing != null) {
                requireReplayMessagesMatch(transactionalRepository.listInboundTurnMessages(persisted.turnId()), turn);
            } else {
                throw new IllegalStateException("normalized turn dedupKey was claimed concurrently; retry");
            }
            return createOrUpdateConversationBinding(transactionalRepository, profile, assistantBinding, turn, now);
        });
        ChannelInboundTurnAudit persisted = repository.findInboundTurnByDedupKey(turn.dedupKey())
            .orElseThrow(() -> new IllegalStateException("normalized turn was not persisted: " + turn.dedupKey()));
        return new ChannelInboundTurnIngestResult(persisted, existing != null, binding);
    }

    private void validateHeaders(NormalizedChannelInboundTurn turn, NormalizedChannelEventHeaders headers) {
        if (headers == null) {
            throw new IllegalArgumentException("normalizedTurn.headers are required");
        }
        String registrationId = NormalizedChannelEventValidator.requireText(
            headers.registrationId(),
            "X-AgentYard-Extension-Registration-Id"
        );
        String descriptorType = NormalizedChannelEventValidator.requireText(
            headers.descriptorType(),
            "X-AgentYard-Extension-Descriptor-Type"
        );
        String descriptorId = NormalizedChannelEventValidator.requireText(
            headers.descriptorId(),
            "X-AgentYard-Extension-Descriptor-Id"
        );
        NormalizedChannelEventValidator.requireText(headers.traceId(), "X-AgentYard-Trace-Id");
        NormalizedChannelEventValidator.requireText(headers.requestId(), "X-AgentYard-Request-Id");
        String idempotencyKey = NormalizedChannelEventValidator.requireText(headers.idempotencyKey(), "Idempotency-Key");

        if (!DescriptorType.CHANNEL_PROVIDER.wireValue().equals(descriptorType)) {
            throw new IllegalArgumentException("X-AgentYard-Extension-Descriptor-Type must be CHANNEL_PROVIDER");
        }
        if (!descriptorId.equals(turn.providerType())) {
            throw new IllegalArgumentException("X-AgentYard-Extension-Descriptor-Id must equal normalizedTurn.providerType");
        }
        if (!idempotencyKey.equals(turn.dedupKey())) {
            throw new IllegalArgumentException("Idempotency-Key must equal normalizedTurn.dedupKey");
        }
        NormalizedChannelEventValidator.validateDedupKey(idempotencyKey);

        boolean exposesProvider = registrationService.services().stream()
            .anyMatch(registration -> registration.registrationId().equals(registrationId)
                && registration.exposes().channelProviderTypes().contains(turn.providerType()));
        if (!exposesProvider) {
            throw new IllegalArgumentException("extension registration does not expose channel provider: " + turn.providerType());
        }
    }

    private static void validateTurn(NormalizedChannelInboundTurn turn) {
        if (turn == null) {
            throw new IllegalArgumentException("normalizedTurn is required");
        }
        NormalizedChannelEventValidator.requireText(turn.providerType(), "normalizedTurn.providerType");
        NormalizedChannelEventValidator.requireText(turn.channelProfileId(), "normalizedTurn.channelProfileId");
        NormalizedChannelEventValidator.validateDedupKey(turn.dedupKey());
        NormalizedChannelEventValidator.requireText(turn.externalConversationId(), "normalizedTurn.externalConversationId");
        if (turn.conversation() == null) {
            throw new IllegalArgumentException("normalizedTurn.conversation is required");
        }
        if (NormalizedChannelEventValidator.hasText(turn.conversation().externalConversationId())
            && !turn.externalConversationId().equals(turn.conversation().externalConversationId())) {
            throw new IllegalArgumentException("normalizedTurn.conversation.externalConversationId must equal externalConversationId");
        }
        if (turn.traceContext() == null || !NormalizedChannelEventValidator.hasText(turn.traceContext().traceparent())) {
            throw new IllegalArgumentException("normalizedTurn.traceContext.traceparent is required");
        }
        if (turn.sender() != null) {
            validateSender(turn.sender(), "normalizedTurn.sender");
        }
        if (turn.messages().isEmpty()) {
            throw new IllegalArgumentException("normalizedTurn.messages is required");
        }
        for (int index = 0; index < turn.messages().size(); index += 1) {
            NormalizedChannelTurnMessage message = turn.messages().get(index);
            if (message == null) {
                throw new IllegalArgumentException("normalizedTurn.messages[" + index + "] is required");
            }
            NormalizedChannelEventValidator.requireText(
                message.externalMessageId(),
                "normalizedTurn.messages[" + index + "].externalMessageId"
            );
            if (message.role() == null) {
                throw new IllegalArgumentException("normalizedTurn.messages[" + index + "].role is required");
            }
            if (message.sender() == null && turn.sender() == null) {
                throw new IllegalArgumentException("normalizedTurn.messages[" + index + "].sender is required");
            }
            if (message.sender() != null) {
                validateSender(message.sender(), "normalizedTurn.messages[" + index + "].sender");
            }
        }
    }

    private static void validateSender(NormalizedChannelMessageSender sender, String field) {
        if (sender.senderType() == null) {
            throw new IllegalArgumentException(field + ".senderType is required");
        }
        NormalizedChannelEventValidator.requireText(sender.senderName(), field + ".senderName");
    }

    private ChannelGatewayProfile requireProfile(NormalizedChannelInboundTurn turn) {
        ChannelGatewayProfile profile = repository.findProfile(turn.channelProfileId())
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + turn.channelProfileId()));
        if (profile.status() != ChannelProfileStatus.ACTIVE) {
            throw new IllegalArgumentException("channel profile is not ACTIVE: " + profile.id());
        }
        if (!profile.inboundEnabled()) {
            throw new IllegalArgumentException("channel profile inbound is disabled: " + profile.id());
        }
        if (!profile.providerType().equals(turn.providerType())) {
            throw new IllegalArgumentException("channel profile providerType does not match normalizedTurn.providerType");
        }
        return profile;
    }

    private static ChannelAssistantBinding requireAssistantBinding(ChannelGatewayProfile profile) {
        ChannelAssistantBinding assistantBinding = profile.assistantBinding();
        if (assistantBinding == null || !NormalizedChannelEventValidator.hasText(assistantBinding.assistantId())) {
            throw new IllegalArgumentException("channel profile assistantBinding.assistantId is required for inbound message turns");
        }
        return assistantBinding;
    }

    private static void requireSameTurnSurface(ChannelInboundTurnAudit existing, NormalizedChannelInboundTurn turn) {
        if (!existing.channelProfileId().equals(turn.channelProfileId())
            || !existing.providerType().equals(turn.providerType())
            || !existing.externalConversationId().equals(turn.externalConversationId())) {
            throw new IllegalArgumentException("normalized turn dedupKey already belongs to a different turn surface");
        }
    }

    private static void requireReplayMessagesMatch(
        List<ChannelInboundTurnMessageAudit> persistedMessages,
        NormalizedChannelInboundTurn replay
    ) {
        if (persistedMessages.size() != replay.messages().size()) {
            throw new IllegalArgumentException("normalized turn dedupKey replay messages do not match persisted turn");
        }
        for (int index = 0; index < replay.messages().size(); index += 1) {
            ChannelInboundTurnMessageAudit persisted = persistedMessages.get(index);
            NormalizedChannelTurnMessage incoming = replay.messages().get(index);
            if (persisted.requestIndex() != index || !persisted.externalMessageId().equals(incoming.externalMessageId())) {
                throw new IllegalArgumentException("normalized turn dedupKey replay messages do not match persisted turn");
            }
        }
    }

    private static ChannelInboundTurnMessageAudit toAuditMessage(
        ChannelInboundTurnAudit audit,
        NormalizedChannelInboundTurn turn,
        int requestIndex,
        Instant now
    ) {
        NormalizedChannelTurnMessage message = turn.messages().get(requestIndex);
        return new ChannelInboundTurnMessageAudit(
            audit.turnId(),
            audit.channelProfileId(),
            audit.externalConversationId(),
            requestIndex,
            message.externalEventId(),
            message.externalMessageId(),
            message.occurredAt(),
            message.role(),
            message.sender() == null ? turn.sender() : message.sender(),
            message.type(),
            message.text(),
            message.attachments(),
            message.metadata(),
            ChannelInboundTurnMessageStatus.RECEIVED,
            null,
            null,
            now,
            now
        );
    }

    private static ChannelConversationBinding createOrUpdateConversationBinding(
        ChannelAdminRepository repository,
        ChannelGatewayProfile profile,
        ChannelAssistantBinding assistantBinding,
        NormalizedChannelInboundTurn turn,
        Instant now
    ) {
        ChannelConversationBinding existing = repository.findBindingByProfileAndExternalConversation(
            profile.id(),
            turn.externalConversationId()
        ).orElse(null);
        String customerId = NormalizedChannelEventValidator.hasText(turn.externalUserId())
            ? turn.externalUserId()
            : turn.externalConversationId();
        boolean canReuseSession = existing != null
            && Objects.equals(existing.assistantId(), assistantBinding.assistantId())
            && Objects.equals(existing.customerId(), customerId);
        ChannelConversationBinding binding = new ChannelConversationBinding(
            existing == null ? nextId("channel-binding") : existing.id(),
            profile.id(),
            turn.externalConversationId(),
            turn.externalUserId(),
            assistantBinding.assistantId(),
            customerId,
            canReuseSession ? existing.sessionId() : null,
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            existing == null ? now : existing.createdAt(),
            now
        );
        repository.saveBindingForConversation(binding);
        return binding;
    }

    private static Map<String, Object> traceContextMap(NormalizedChannelInboundTurn turn) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("traceparent", turn.traceContext().traceparent());
        if (turn.traceContext().tracestate() != null) {
            trace.put("tracestate", turn.traceContext().tracestate());
        }
        return Map.copyOf(trace);
    }

    static NormalizedChannelMessageSender customerSender(String senderId, String senderName, Map<String, Object> metadata) {
        return new NormalizedChannelMessageSender(
            NormalizedChannelSenderType.CUSTOMER,
            senderId,
            senderName,
            metadata == null ? Map.of() : metadata
        );
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
