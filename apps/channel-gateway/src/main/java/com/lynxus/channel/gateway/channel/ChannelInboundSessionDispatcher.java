package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessage;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessageInput;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ChannelInboundSessionDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ChannelInboundSessionDispatcher.class);

    private final ChannelAdminRepository repository;
    private final ChannelSessionRuntimeClient sessionRuntimeClient;
    private final ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient;
    private final Executor executor;

    @Autowired
    public ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelSessionRuntimeClient sessionRuntimeClient,
        ChannelBindingSnapshotRefreshHintClient bindingSnapshotRefreshHintClient,
        @Qualifier(ChannelGatewayAsyncConfiguration.CHANNEL_INBOUND_SESSION_DISPATCH_EXECUTOR) Executor executor
    ) {
        this.repository = repository;
        this.sessionRuntimeClient = sessionRuntimeClient;
        this.bindingSnapshotRefreshHintClient = bindingSnapshotRefreshHintClient;
        this.executor = executor;
    }

    ChannelInboundSessionDispatcher(
        ChannelAdminRepository repository,
        ChannelSessionRuntimeClient sessionRuntimeClient,
        Executor executor
    ) {
        this(repository, sessionRuntimeClient, null, executor);
    }

    public void dispatchAsync(
        NormalizedChannelInboundEvent event,
        NormalizedChannelInboundEventResult ingestResult
    ) {
        if (!NormalizedChannelEventValidator.triggersSessionBinding(event)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    dispatch(event, ingestResult);
                } catch (RuntimeException error) {
                    log.warn(
                        "failed to dispatch channel inbound event to session runtime: channelProfileId={}, externalConversationId={}, inboundEventId={}, dedupKey={}",
                        event.channelProfileId(),
                        event.externalConversationId(),
                        ingestResult == null ? null : ingestResult.eventId(),
                        event.dedupKey(),
                        error
                    );
                }
            });
        } catch (RejectedExecutionException error) {
            log.warn(
                "failed to queue channel inbound event dispatch: channelProfileId={}, externalConversationId={}, inboundEventId={}, dedupKey={}",
                event.channelProfileId(),
                event.externalConversationId(),
                ingestResult == null ? null : ingestResult.eventId(),
                event.dedupKey(),
                error
            );
        }
    }

    public ChannelInboundSessionDispatchResult dispatch(
        NormalizedChannelInboundEvent event,
        NormalizedChannelInboundEventResult ingestResult
    ) {
        if (!NormalizedChannelEventValidator.triggersSessionBinding(event)) {
            return null;
        }
        ChannelConversationBinding binding = repository.findBindingByProfileAndExternalConversation(
            event.channelProfileId(),
            event.externalConversationId()
        ).orElseThrow(() -> new IllegalStateException("channel conversation binding was not created for inbound event: " + event.dedupKey()));

        ChannelInboundSessionMessageResponse response = sessionRuntimeClient.dispatchInboundMessage(
            new ChannelInboundSessionMessageRequest(
                event.channelProfileId(),
                event.externalConversationId(),
                event.externalMessageId(),
                ingestResult.eventId(),
                event.dedupKey(),
                binding.assistantId(),
                binding.customerId(),
                binding.sessionId(),
                toSessionMessageInput(event)
            )
        );
        ChannelConversationBinding updatedBinding = attachSession(binding, response.sessionId());
        if (!Objects.equals(updatedBinding.sessionId(), binding.sessionId())) {
            sendBindingSnapshotRefreshHint(updatedBinding);
        }
        log.info(
            "dispatched channel inbound event to session runtime: channelProfileId={}, externalConversationId={}, inboundEventId={}, duplicate={}, sessionId={}",
            event.channelProfileId(),
            event.externalConversationId(),
            ingestResult.eventId(),
            ingestResult.duplicate(),
            response.sessionId()
        );
        return new ChannelInboundSessionDispatchResult(ingestResult.eventId(), response.sessionId(), updatedBinding.id());
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

    private static SessionMessageInput toSessionMessageInput(NormalizedChannelInboundEvent event) {
        NormalizedChannelMessage message = event.message();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerType", event.providerType());
        metadata.put("channelProfileId", event.channelProfileId());
        metadata.put("externalConversationId", event.externalConversationId());
        metadata.put("externalMessageId", event.externalMessageId());
        metadata.put("dedupKey", event.dedupKey());
        if (event.metadata() != null) {
            metadata.putAll(event.metadata());
        }
        return new SessionMessageInput(List.of(toMessageBlock(message)), metadata);
    }

    private static Map<String, Object> toMessageBlock(NormalizedChannelMessage message) {
        Map<String, Object> block = new LinkedHashMap<>();
        String type = message == null || message.type() == null ? "TEXT" : message.type().toUpperCase(Locale.ROOT);
        block.put("type", type);
        if ("TEXT".equals(type) && message != null && message.text() != null) {
            block.put("text", message.text());
        }
        if ("RICH_TEXT".equals(type) && message != null && message.text() != null) {
            block.put("content", message.text());
        }
        return Map.copyOf(block);
    }

    public record ChannelInboundSessionDispatchResult(
        String inboundEventId,
        String sessionId,
        String bindingId
    ) {
    }
}
