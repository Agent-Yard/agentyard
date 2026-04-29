package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelAdminService;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class FeishuWebhookService {
    private static final String PROVIDER = "feishu";

    private final ChannelAdminService channelAdminService;
    private final ObjectMapper objectMapper;

    public FeishuWebhookService(ChannelAdminService channelAdminService, ObjectMapper objectMapper) {
        this.channelAdminService = channelAdminService;
        this.objectMapper = objectMapper;
    }

    public Object handleWebhook(Map<String, Object> payload, Map<String, String> headers) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        String appId = firstNonBlank(
            readString(body.get("app_id")),
            readNestedString(body, "header", "app_id"),
            readNestedString(body, "event", "app_id")
        );
        ChannelProfile profile = channelAdminService.findProfileByProviderAppId(PROVIDER, appId);
        validateVerificationToken(profile, body);

        if ("url_verification".equals(readString(body.get("type")))) {
            String challenge = readString(body.get("challenge"));
            if (challenge == null || challenge.isBlank()) {
                throw new IllegalArgumentException("feishu challenge is required");
            }
            return Map.of("challenge", challenge);
        }

        Map<String, Object> normalizedPayload = normalizePayload(body, headers, appId);
        String dedupKey = dedupKey(normalizedPayload, body);
        ChannelInboundEvent existing = channelAdminService.findInboundEventByDedupKey(dedupKey);
        if (existing != null) {
            return Map.of(
                "status", "ok",
                "eventId", existing.eventId(),
                "duplicate", true
            );
        }

        Instant now = Instant.now();
        ChannelInboundEvent event = new ChannelInboundEvent(
            channelAdminService.nextId("channel-inbound-event"),
            profile.id(),
            PROVIDER,
            readString(normalizedPayload.get("eventType")),
            readString(normalizedPayload.get("externalEventId")),
            readString(normalizedPayload.get("externalConversationId")),
            readString(normalizedPayload.get("externalMessageId")),
            dedupKey,
            body,
            normalizedPayload,
            ChannelInboundEventStatus.RECEIVED,
            now,
            now
        );
        channelAdminService.saveInboundEvent(event);
        return Map.of(
            "status", "ok",
            "eventId", event.eventId(),
            "duplicate", false
        );
    }

    private void validateVerificationToken(ChannelProfile profile, Map<String, Object> payload) {
        Object configuredToken = profile.config().get("verificationToken");
        String expectedToken = configuredToken == null ? null : String.valueOf(configuredToken).trim();
        String actualToken = readString(payload.get("token"));
        if (expectedToken != null && !expectedToken.isBlank() && actualToken != null && !expectedToken.equals(actualToken)) {
            throw new IllegalArgumentException("feishu verification token mismatch");
        }
    }

    private Map<String, Object> normalizePayload(Map<String, Object> body, Map<String, String> headers, String appId) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("providerType", PROVIDER);
        normalized.put("appId", appId);
        normalized.put("eventType", firstNonBlank(
            readNestedString(body, "header", "event_type"),
            readString(body.get("type")),
            "UNKNOWN"
        ));
        normalized.put("externalEventId", firstNonBlank(
            readNestedString(body, "header", "event_id"),
            readString(body.get("event_id"))
        ));
        normalized.put("externalConversationId", firstNonBlank(
            readNestedString(body, "event", "open_chat_id"),
            readNestedString(body, "event", "chat_id"),
            readNestedString(body, "event", "conversation_id")
        ));
        normalized.put("externalMessageId", firstNonBlank(
            readNestedString(body, "event", "message", "message_id"),
            readNestedString(body, "event", "message_id")
        ));
        String externalUserId = firstNonBlank(
            readNestedString(body, "event", "sender", "sender_id", "open_id"),
            readNestedString(body, "event", "sender", "sender_id", "user_id"),
            readNestedString(body, "event", "open_id")
        );
        if (externalUserId != null) {
            normalized.put("externalUserId", externalUserId);
        }
        String timestamp = firstNonBlank(headers.get("X-Lark-Request-Timestamp"), headers.get("X-Request-Timestamp"));
        if (timestamp != null) {
            normalized.put("requestTimestamp", timestamp);
        }
        normalized.put("signaturePresent", headers.containsKey("X-Lark-Signature"));
        return Map.copyOf(normalized);
    }

    private String dedupKey(Map<String, Object> normalizedPayload, Map<String, Object> rawPayload) {
        String externalEventId = readString(normalizedPayload.get("externalEventId"));
        if (externalEventId != null && !externalEventId.isBlank()) {
            return "feishu:event:" + externalEventId;
        }
        String externalMessageId = readString(normalizedPayload.get("externalMessageId"));
        if (externalMessageId != null && !externalMessageId.isBlank()) {
            return "feishu:message:" + externalMessageId;
        }
        return "feishu:payload:" + hashIdempotencySeed(stableStringify(rawPayload));
    }

    private String stableStringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            var entries = map.entrySet().stream()
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .toList();
            for (int index = 0; index < entries.size(); index += 1) {
                if (index > 0) {
                    builder.append(',');
                }
                Map.Entry<?, ?> entry = entries.get(index);
                builder.append(writeJson(String.valueOf(entry.getKey())));
                builder.append(':');
                builder.append(stableStringify(entry.getValue()));
            }
            return builder.append('}').toString();
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder builder = new StringBuilder("[");
            int index = 0;
            for (Object item : iterable) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(stableStringify(item));
                index += 1;
            }
            return builder.append(']').toString();
        }
        return writeJson(value);
    }

    private String hashIdempotencySeed(String seed) {
        int hash = 0x811c9dc5;
        byte[] bytes = seed.getBytes(StandardCharsets.UTF_8);
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x01000193;
        }
        return String.format("%08x", hash);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) { // noqa: BLE001
            throw new IllegalStateException("failed to serialize feishu webhook payload", error);
        }
    }

    private static String readNestedString(Object root, String... keys) {
        Object current = root;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return readString(current);
    }

    private static String readString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String... candidates) {
        return java.util.Arrays.stream(candidates)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(candidate -> !candidate.isEmpty())
            .findFirst()
            .orElse(null);
    }
}
