package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.extension.ChannelProviderDescriptor;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistry;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundPayload;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundResolvedTemplate;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ResolvedChannelTemplate;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OutboundDeliveryExecutionService {
    private static final String MESSAGE_TYPE_CARD = "CARD";

    private final ChannelAdminRepository repository;
    private final ChannelTemplateBindingResolver templateBindingResolver;
    private final ChannelProviderRegistry channelProviderRegistry;
    private final ChannelProviderOutboundSender outboundSender;

    public OutboundDeliveryExecutionService(
        ChannelAdminRepository repository,
        ChannelTemplateBindingResolver templateBindingResolver,
        ChannelProviderRegistry channelProviderRegistry,
        ChannelProviderOutboundSender outboundSender
    ) {
        this.repository = repository;
        this.templateBindingResolver = templateBindingResolver;
        this.channelProviderRegistry = channelProviderRegistry;
        this.outboundSender = outboundSender;
    }

    public ChannelOutboundDelivery deliver(ChannelOutboundDeliveryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("outboundDelivery request is required");
        }
        String channelProfileId = requireText(request.channelProfileId(), "outboundDelivery.channelProfileId");
        ChannelOutboundProfileSnapshot profile = repository.findOutboundProfileSnapshot(channelProfileId)
            .orElseThrow(() -> new NoSuchElementException("channel profile not found: " + channelProfileId));
        String deliveryId = nextId("channel-outbound-delivery");
        String idempotencyKey = deliveryId;
        Instant now = Instant.now();
        ChannelOutboundDelivery delivery = null;
        Map<String, Object> payload = Map.of();

        try {
            String externalConversationId = requireText(request.externalConversationId(), "outboundDelivery.externalConversationId");
            Map<String, Object> messageBlock = acceptedMessageBlock(request.messageBlock());
            payload = payloadMap(new ChannelOutboundPayload(externalConversationId, messageBlock, null));
            delivery = delivery(
                deliveryId,
                profile,
                request,
                idempotencyKey,
                payload,
                ChannelOutboundDeliveryStatus.PENDING,
                0,
                null,
                now,
                now
            );
            repository.saveOutboundDelivery(delivery);

            PreparedOutbound prepared = prepare(profile, request, externalConversationId, messageBlock);
            payload = payloadMap(prepared.payload());
            delivery = delivery(
                deliveryId,
                profile,
                request,
                idempotencyKey,
                payload,
                ChannelOutboundDeliveryStatus.PENDING,
                0,
                null,
                delivery.createdAt(),
                Instant.now()
            );
            repository.saveOutboundDelivery(delivery);

            ChannelProviderDescriptor descriptor = channelProviderRegistry.requireProvider(profile.providerType());
            TraceIds traceIds = TraceIds.fromOrCreate(request.traceContext());
            delivery = delivery(
                deliveryId,
                profile,
                request,
                idempotencyKey,
                payload,
                ChannelOutboundDeliveryStatus.SENDING,
                1,
                null,
                delivery.createdAt(),
                Instant.now()
            );
            repository.saveOutboundDelivery(delivery);

            outboundSender.send(new ChannelOutboundInvocation(
                descriptor,
                profile,
                idempotencyKey,
                traceIds,
                prepared.payload()
            ));
            ChannelOutboundDelivery sent = delivery(
                deliveryId,
                profile,
                request,
                idempotencyKey,
                payload,
                ChannelOutboundDeliveryStatus.SENT,
                1,
                null,
                delivery.createdAt(),
                Instant.now()
            );
            repository.saveOutboundDelivery(sent);
            return sent;
        } catch (OutboundDeliveryValidationException error) {
            Map<String, Object> failedPayload = error.payload() == null ? payload : error.payload();
            return fail(delivery, deliveryId, profile, request, idempotencyKey, failedPayload, 0, error.getMessage(), now);
        } catch (IllegalArgumentException error) {
            return fail(delivery, deliveryId, profile, request, idempotencyKey, payload, delivery == null ? 0 : delivery.attemptCount(), error.getMessage(), now);
        } catch (Exception error) {
            return fail(delivery, deliveryId, profile, request, idempotencyKey, payload, delivery == null ? 0 : delivery.attemptCount(), OutboundErrorSanitizer.sanitize(error), now);
        }
    }

    private PreparedOutbound prepare(
        ChannelOutboundProfileSnapshot profile,
        ChannelOutboundDeliveryRequest request,
        String externalConversationId,
        Map<String, Object> messageBlock
    ) {
        if (profile.status() != ChannelProfileStatus.ACTIVE) {
            throw new OutboundDeliveryValidationException("channel profile is not active");
        }
        String assistantId = requireText(request.assistantId(), "outboundDelivery.assistantId");
        if (profile.assistantBinding() != null
            && profile.assistantBinding().assistantId() != null
            && !assistantId.equals(profile.assistantBinding().assistantId())) {
            throw new OutboundDeliveryValidationException("outbound assistant does not match channel profile assistant binding");
        }
        String messageType = requireText(stringValue(messageBlock.get("type")), "messageBlock.type").toUpperCase(Locale.ROOT);
        switch (messageType) {
            case "TEXT" -> validateTextBlock(messageBlock);
            case "IMAGE" -> validateImageBlock(messageBlock);
            case "RICH_TEXT" -> validateRichTextBlock(messageBlock);
            case MESSAGE_TYPE_CARD -> {
                return new PreparedOutbound(prepareCardPayload(profile, assistantId, externalConversationId, messageBlock));
            }
            default -> throw new OutboundDeliveryValidationException("unsupported canonical messageBlock.type: " + messageType);
        }
        return new PreparedOutbound(new ChannelOutboundPayload(externalConversationId, messageBlock, null));
    }

    private ChannelOutboundPayload prepareCardPayload(
        ChannelOutboundProfileSnapshot profile,
        String assistantId,
        String externalConversationId,
        Map<String, Object> messageBlock
    ) {
        String messageSubtype = requireText(stringValue(messageBlock.get("cardType")), "messageBlock.cardType");
        String messageVersion = requireText(stringValue(messageBlock.get("version")), "messageBlock.version");
        ResolvedChannelTemplate resolved = templateBindingResolver.resolve(
            assistantId,
            profile.channelProfileId(),
            MESSAGE_TYPE_CARD,
            messageSubtype,
            messageVersion
        ).orElseThrow(() -> new OutboundDeliveryValidationException("enabled channel template binding was not found"));
        ChannelOutboundResolvedTemplate outboundTemplate = new ChannelOutboundResolvedTemplate(
            MESSAGE_TYPE_CARD,
            messageSubtype,
            messageVersion,
            resolved.externalTemplateId(),
            resolved.externalTemplateVersion()
        );
        ChannelOutboundPayload payload = new ChannelOutboundPayload(externalConversationId, messageBlock, outboundTemplate);
        try {
            ChannelTemplateBindingDataValidator.validate(resolved.variableSchema(), cardData(messageBlock));
        } catch (IllegalArgumentException error) {
            throw new OutboundDeliveryValidationException(error.getMessage(), payloadMap(payload));
        }
        return payload;
    }

    private static void validateTextBlock(Map<String, Object> messageBlock) {
        requireText(stringValue(messageBlock.get("text")), "messageBlock.text");
    }

    private static void validateImageBlock(Map<String, Object> messageBlock) {
        requireText(stringValue(messageBlock.get("url")), "messageBlock.url");
        Object mimeType = messageBlock.get("mimeType");
        if (mimeType != null) {
            requireText(stringValue(mimeType), "messageBlock.mimeType");
        }
    }

    private static void validateRichTextBlock(Map<String, Object> messageBlock) {
        String format = requireText(stringValue(messageBlock.get("format")), "messageBlock.format");
        if (!"MARKDOWN".equals(format.toUpperCase(Locale.ROOT))) {
            throw new OutboundDeliveryValidationException("unsupported canonical messageBlock.format: " + format);
        }
        requireText(stringValue(messageBlock.get("content")), "messageBlock.content");
    }

    private ChannelOutboundDelivery fail(
        ChannelOutboundDelivery current,
        String deliveryId,
        ChannelOutboundProfileSnapshot profile,
        ChannelOutboundDeliveryRequest request,
        String idempotencyKey,
        Map<String, Object> payload,
        int attemptCount,
        String error,
        Instant fallbackCreatedAt
    ) {
        Instant createdAt = current == null ? fallbackCreatedAt : current.createdAt();
        ChannelOutboundDelivery failed = delivery(
            deliveryId,
            profile,
            request,
            idempotencyKey,
            payload == null ? Map.of() : payload,
            ChannelOutboundDeliveryStatus.FAILED,
            attemptCount,
            OutboundErrorSanitizer.sanitize(error),
            createdAt,
            Instant.now()
        );
        repository.saveOutboundDelivery(failed);
        return failed;
    }

    private static ChannelOutboundDelivery delivery(
        String deliveryId,
        ChannelOutboundProfileSnapshot profile,
        ChannelOutboundDeliveryRequest request,
        String idempotencyKey,
        Map<String, Object> payload,
        ChannelOutboundDeliveryStatus status,
        int attemptCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new ChannelOutboundDelivery(
            deliveryId,
            profile.channelProfileId(),
            profile.providerType(),
            normalizeOptionalText(request.sessionId()),
            normalizeOptionalText(request.sessionMessageId()),
            normalizeOptionalText(request.externalConversationId()),
            idempotencyKey,
            payload,
            status,
            attemptCount,
            lastError,
            createdAt,
            updatedAt
        );
    }

    private static Map<String, Object> payloadMap(ChannelOutboundPayload payload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("externalConversationId", payload.externalConversationId());
        result.put("messageBlock", payload.messageBlock());
        if (payload.resolvedTemplate() != null) {
            result.put("resolvedTemplate", resolvedTemplateMap(payload.resolvedTemplate()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> resolvedTemplateMap(ChannelOutboundResolvedTemplate template) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageType", template.messageType());
        result.put("messageSubtype", template.messageSubtype());
        result.put("messageVersion", template.messageVersion());
        result.put("externalTemplateId", template.externalTemplateId());
        result.put("externalTemplateVersion", template.externalTemplateVersion());
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> acceptedMessageBlock(Map<String, Object> messageBlock) {
        if (messageBlock == null || messageBlock.isEmpty()) {
            throw new OutboundDeliveryValidationException("messageBlock is required");
        }
        rejectUnsupportedOutboundMaterial(messageBlock);
        return copyObjectMap(messageBlock);
    }

    private static void rejectUnsupportedOutboundMaterial(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() instanceof String stringKey ? stringKey : String.valueOf(entry.getKey());
                String compact = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (compact.equals("templatekey")
                    || compact.equals("externaltemplateid")
                    || compact.equals("externaltemplateversion")
                    || compact.equals("providernativepayload")
                    || compact.equals("providerpayload")
                    || compact.equals("templatebody")
                    || compact.equals("cardjson")
                    || compact.equals("externalsecretref")
                    || compact.equals("authorization")
                    || compact.equals("accesstoken")
                    || compact.equals("refreshtoken")
                    || compact.equals("apikey")
                    || compact.contains("credential")) {
                    throw new OutboundDeliveryValidationException("messageBlock contains unsupported provider-native or credential field");
                }
                rejectUnsupportedOutboundMaterial(entry.getValue());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                rejectUnsupportedOutboundMaterial(item);
            }
            return;
        }
        if (value instanceof String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("vault://") || normalized.contains("bearer ")) {
                throw new OutboundDeliveryValidationException("messageBlock contains unsupported provider-native or credential field");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cardData(Map<String, Object> messageBlock) {
        Object data = messageBlock.get("data");
        if (data == null) {
            return Map.of();
        }
        if (!(data instanceof Map<?, ?> map)) {
            throw new OutboundDeliveryValidationException("messageBlock.data must be an object");
        }
        return copyObjectMap((Map<String, Object>) map);
    }

    private static Map<String, Object> copyObjectMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, copyValue(value)));
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (key instanceof String stringKey) {
                    result.put(stringKey, copyValue(nestedValue));
                }
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            for (Object item : iterable) {
                result.add(copyValue(item));
            }
            return List.copyOf(result);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new OutboundDeliveryValidationException(field + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record PreparedOutbound(ChannelOutboundPayload payload) {
    }

    private static final class OutboundDeliveryValidationException extends IllegalArgumentException {
        private final Map<String, Object> payload;

        private OutboundDeliveryValidationException(String message) {
            this(message, null);
        }

        private OutboundDeliveryValidationException(String message, Map<String, Object> payload) {
            super(message);
            this.payload = payload;
        }

        private Map<String, Object> payload() {
            return payload;
        }
    }
}
