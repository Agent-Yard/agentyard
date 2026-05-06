package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelAdminService;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class FeishuWebhookService {
    private static final String PROVIDER = "feishu";

    private final ChannelAdminService channelAdminService;
    private final ObjectMapper objectMapper;
    private final FeishuIntegrationAccountRuntimeProvider accountRuntimeProvider;

    public FeishuWebhookService(
        ChannelAdminService channelAdminService,
        ObjectMapper objectMapper,
        FeishuIntegrationAccountRuntimeProvider accountRuntimeProvider
    ) {
        this.channelAdminService = channelAdminService;
        this.objectMapper = objectMapper;
        this.accountRuntimeProvider = accountRuntimeProvider;
    }

    public Object handleWebhook(Map<String, Object> payload, Map<String, String> headers) {
        Map<String, Object> body = payload == null ? Map.of() : payload;
        String appId = firstNonBlank(
            readString(body.get("app_id")),
            readNestedString(body, "header", "app_id"),
            readNestedString(body, "event", "app_id")
        );
        ProfileAccount profileAccount = findProfileAccountByAppId(appId);
        validateVerificationToken(profileAccount.account(), body);

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
            profileAccount.profile().id(),
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

    private ProfileAccount findProfileAccountByAppId(String appId) {
        String normalizedAppId = requireText(appId, "feishu appId");
        Map<String, FeishuIntegrationAccountRuntime> accountsById = new HashMap<>();
        for (ChannelGatewayProfile profile : channelAdminService.listProfilesByProvider(PROVIDER)) {
            String accountId = profile.accountId();
            if (accountId == null || accountId.isBlank()) {
                continue;
            }
            FeishuIntegrationAccountRuntime account = accountsById.computeIfAbsent(accountId, accountRuntimeProvider::load);
            account.requireEnabledFeishuChannelProvider();
            if (normalizedAppId.equals(account.appId())) {
                return new ProfileAccount(profile, account);
            }
        }
        throw new NoSuchElementException("channel profile not found for " + PROVIDER + " integration account appId: " + normalizedAppId);
    }

    private void validateVerificationToken(FeishuIntegrationAccountRuntime account, Map<String, Object> payload) {
        String expectedToken = account.verificationToken();
        String actualToken = readString(payload.get("token"));
        if (expectedToken != null && !expectedToken.equals(actualToken)) {
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

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private record ProfileAccount(ChannelGatewayProfile profile, FeishuIntegrationAccountRuntime account) {
    }
}
