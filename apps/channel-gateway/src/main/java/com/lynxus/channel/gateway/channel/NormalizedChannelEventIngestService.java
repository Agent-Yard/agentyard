package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NormalizedChannelEventIngestService {
    private final ChannelAdminRepository repository;
    private final ExtensionRegistrationService registrationService;

    public NormalizedChannelEventIngestService(
        ChannelAdminRepository repository,
        ExtensionRegistrationService registrationService
    ) {
        this.repository = repository;
        this.registrationService = registrationService;
    }

    public NormalizedChannelInboundEventResult ingest(
        NormalizedChannelInboundEvent event,
        NormalizedChannelEventHeaders headers
    ) {
        validateHeaders(event, headers);
        NormalizedChannelEventValidator.validateEventTypeMatrix(event);
        ChannelGatewayProfile profile = requireProfile(event);
        if (NormalizedChannelEventValidator.triggersSessionBinding(event)) {
            requireAssistantBinding(profile);
        }

        ChannelInboundEvent existing = repository.findInboundEventByDedupKey(event.dedupKey()).orElse(null);
        if (existing != null) {
            requireSameEventSurface(existing, event);
            return new NormalizedChannelInboundEventResult(existing.eventId(), true);
        }

        Instant now = Instant.now();
        ChannelInboundEvent savedEvent = new ChannelInboundEvent(
            nextId("channel-inbound-event"),
            profile.id(),
            profile.providerType(),
            event.eventType().name(),
            event.externalEventId(),
            event.externalConversationId(),
            event.externalMessageId(),
            event.dedupKey(),
            event.rawPayload(),
            event.normalizedPayload(),
            ChannelInboundEventStatus.RECEIVED,
            now,
            now
        );
        boolean inserted = repository.transactionResult(transactionalRepository -> {
            boolean insertedEvent = transactionalRepository.saveInboundEventIfAbsent(savedEvent);
            if (insertedEvent && NormalizedChannelEventValidator.triggersSessionBinding(event)) {
                createOrUpdateConversationBinding(transactionalRepository, profile, event, now);
            }
            return insertedEvent;
        });
        if (!inserted) {
            ChannelInboundEvent duplicate = repository.findInboundEventByDedupKey(event.dedupKey())
                .orElseThrow(() -> new IllegalStateException("normalized event duplicate disappeared: " + event.dedupKey()));
            requireSameEventSurface(duplicate, event);
            return new NormalizedChannelInboundEventResult(duplicate.eventId(), true);
        }
        return new NormalizedChannelInboundEventResult(savedEvent.eventId(), false);
    }

    private void validateHeaders(NormalizedChannelInboundEvent event, NormalizedChannelEventHeaders headers) {
        if (headers == null) {
            throw new IllegalArgumentException("normalizedEvent.headers are required");
        }
        String registrationId = NormalizedChannelEventValidator.requireText(
            headers.registrationId(),
            "X-Lynxus-Extension-Registration-Id"
        );
        String descriptorType = NormalizedChannelEventValidator.requireText(
            headers.descriptorType(),
            "X-Lynxus-Extension-Descriptor-Type"
        );
        String descriptorId = NormalizedChannelEventValidator.requireText(
            headers.descriptorId(),
            "X-Lynxus-Extension-Descriptor-Id"
        );
        NormalizedChannelEventValidator.requireText(headers.traceId(), "X-Lynxus-Trace-Id");
        NormalizedChannelEventValidator.requireText(headers.requestId(), "X-Lynxus-Request-Id");
        String idempotencyKey = NormalizedChannelEventValidator.requireText(headers.idempotencyKey(), "Idempotency-Key");

        if (!DescriptorType.CHANNEL_PROVIDER.wireValue().equals(descriptorType)) {
            throw new IllegalArgumentException("X-Lynxus-Extension-Descriptor-Type must be CHANNEL_PROVIDER");
        }
        if (!descriptorId.equals(event.providerType())) {
            throw new IllegalArgumentException("X-Lynxus-Extension-Descriptor-Id must equal normalizedEvent.providerType");
        }
        if (!idempotencyKey.equals(event.dedupKey())) {
            throw new IllegalArgumentException("Idempotency-Key must equal normalizedEvent.dedupKey");
        }
        NormalizedChannelEventValidator.validateDedupKey(idempotencyKey);

        boolean exposesProvider = registrationService.services().stream()
            .anyMatch(registration -> registration.registrationId().equals(registrationId)
                && registration.exposes().channelProviderTypes().contains(event.providerType()));
        if (!exposesProvider) {
            throw new IllegalArgumentException("extension registration does not expose channel provider: " + event.providerType());
        }
    }

    private ChannelGatewayProfile requireProfile(NormalizedChannelInboundEvent event) {
        ChannelGatewayProfile profile = repository.findProfile(event.channelProfileId())
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + event.channelProfileId()));
        if (profile.status() != ChannelProfileStatus.ACTIVE) {
            throw new IllegalArgumentException("channel profile is not ACTIVE: " + profile.id());
        }
        if (!profile.inboundEnabled()) {
            throw new IllegalArgumentException("channel profile inbound is disabled: " + profile.id());
        }
        if (!profile.providerType().equals(event.providerType())) {
            throw new IllegalArgumentException("channel profile providerType does not match normalizedEvent.providerType");
        }
        return profile;
    }

    private void createOrUpdateConversationBinding(
        ChannelAdminRepository repository,
        ChannelGatewayProfile profile,
        NormalizedChannelInboundEvent event,
        Instant now
    ) {
        ChannelAssistantBinding assistantBinding = requireAssistantBinding(profile);
        ChannelConversationBinding existing = repository.findBindingByProfileAndExternalConversation(
            profile.id(),
            event.externalConversationId()
        ).orElse(null);
        String customerId = NormalizedChannelEventValidator.hasText(event.externalUserId())
            ? event.externalUserId()
            : event.externalConversationId();
        ChannelConversationBinding binding = new ChannelConversationBinding(
            existing == null ? nextId("channel-binding") : existing.id(),
            profile.id(),
            event.externalConversationId(),
            event.externalUserId(),
            assistantBinding.assistantId(),
            customerId,
            existing == null ? null : existing.sessionId(),
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            existing == null ? now : existing.createdAt(),
            now
        );
        repository.saveBindingForConversation(binding);
    }

    private static void requireSameEventSurface(ChannelInboundEvent existing, NormalizedChannelInboundEvent event) {
        if (!existing.channelProfileId().equals(event.channelProfileId())
            || !existing.providerType().equals(event.providerType())
            || !existing.eventType().equals(event.eventType().name())) {
            throw new IllegalArgumentException("normalized event dedupKey already belongs to a different event surface");
        }
    }

    private static ChannelAssistantBinding requireAssistantBinding(ChannelGatewayProfile profile) {
        ChannelAssistantBinding assistantBinding = profile.assistantBinding();
        if (assistantBinding == null || !NormalizedChannelEventValidator.hasText(assistantBinding.assistantId())) {
            throw new IllegalArgumentException("channel profile assistantBinding.assistantId is required for inbound message events");
        }
        return assistantBinding;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
