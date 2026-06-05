package com.agentyard.channel.gateway.connector.feishu;

public record FeishuAppCredential(String accountId, String appId, String appSecret) {
    public FeishuAppCredential {
        accountId = requireText(accountId, "feishu accountId");
        appId = requireText(appId, "feishu appId");
        appSecret = requireText(appSecret, "feishu appSecret");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
