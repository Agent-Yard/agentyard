package com.agentyard.channel.gateway.connector.feishu;

import java.util.Map;
import tools.jackson.databind.ObjectMapper;

final class FeishuCardJsonFactory {
    static final String STREAMING_MARKDOWN_ELEMENT_ID = "markdown_1";

    private FeishuCardJsonFactory() {
    }

    static String streamingReplyCard(ObjectMapper objectMapper, String content) {
        return write(objectMapper, Map.of(
            "schema", "2.0",
            "config", Map.of(
                "streaming_mode", true,
                "update_multi", true,
                "summary", Map.of("content", ""),
                "streaming_config", Map.of(
                    "print_frequency_ms", endpointConfig(50),
                    "print_step", endpointConfig(2),
                    "print_strategy", "fast"
                )
            ),
            "body", Map.of(
                "elements", java.util.List.of(markdownElement(content))
            )
        ));
    }

    static String finalReplyCard(ObjectMapper objectMapper, String content) {
        return write(objectMapper, Map.of(
            "schema", "2.0",
            "config", Map.of(
                "streaming_mode", false,
                "update_multi", true,
                "summary", Map.of("content", summary(content))
            ),
            "body", Map.of(
                "elements", java.util.List.of(markdownElement(content))
            )
        ));
    }

    static String streamingOffSettings(ObjectMapper objectMapper) {
        return write(objectMapper, Map.of(
            "config", Map.of("streaming_mode", false)
        ));
    }

    private static Map<String, Object> markdownElement(String content) {
        return Map.of(
            "tag", "markdown",
            "content", content == null ? "" : content,
            "element_id", STREAMING_MARKDOWN_ELEMENT_ID
        );
    }

    private static Map<String, Integer> endpointConfig(int value) {
        return Map.of(
            "default", value,
            "android", value,
            "ios", value,
            "pc", value
        );
    }

    private static String summary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replace('\n', ' ').trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private static String write(ObjectMapper objectMapper, Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("failed to render Feishu card JSON", error);
        }
    }
}
