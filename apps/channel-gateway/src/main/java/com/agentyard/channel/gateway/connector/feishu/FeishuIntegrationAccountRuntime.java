package com.agentyard.channel.gateway.connector.feishu;

import java.util.LinkedHashMap;
import java.util.Map;

record FeishuIntegrationAccountRuntime(
    String accountId,
    String subjectType,
    String subjectId,
    String status,
    Map<String, Object> config,
    Map<String, Object> credential
) {
    FeishuIntegrationAccountRuntime {
        accountId = requireText(accountId, "feishu integration accountId");
        subjectType = requireText(subjectType, "feishu integration account subjectType");
        subjectId = requireText(subjectId, "feishu integration account subjectId");
        status = requireText(status, "feishu integration account status");
        config = immutableObject(config);
        credential = immutableObject(credential);
    }

    void requireEnabledFeishuChannelProvider() {
        if (!"CHANNEL_PROVIDER".equals(subjectType)) {
            throw new IllegalStateException("integration account subjectType is not CHANNEL_PROVIDER: " + accountId);
        }
        if (!FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE.equals(subjectId)) {
            throw new IllegalStateException("integration account subjectId is not feishu: " + accountId);
        }
        if (!"ENABLED".equals(status)) {
            throw new IllegalStateException("integration account is not ENABLED: " + accountId);
        }
    }

    String appId() {
        return readString(config.get("appId"));
    }

    String verificationToken() {
        return readString(credential.get("verificationToken"));
    }

    String appSecret() {
        return readString(credential.get("appSecret"));
    }

    static String readString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(value));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
