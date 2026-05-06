package com.lynxus.channel.gateway.connector.feishu;

final class FeishuApiException extends IllegalStateException {
    private final int code;
    private final String feishuMessage;
    private final String requestId;

    FeishuApiException(String prefix, int code, String feishuMessage, String requestId) {
        super(prefix + ": code=" + code + ", msg=" + feishuMessage + ", requestId=" + requestId);
        this.code = code;
        this.feishuMessage = feishuMessage;
        this.requestId = requestId;
    }

    int code() {
        return code;
    }

    String feishuMessage() {
        return feishuMessage;
    }

    String requestId() {
        return requestId;
    }
}
