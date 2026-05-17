package com.lynxus.channel.gateway.connector.feishu;

record FeishuStreamingReplyCardBlock(
    String blockId,
    String blockType,
    String content,
    boolean completed
) {
    FeishuStreamingReplyCardBlock {
        blockId = requireText(blockId, "blockId");
        blockType = blockType == null || blockType.isBlank() ? "TEXT" : blockType;
        content = content == null ? "" : content;
    }

    FeishuStreamingReplyCardBlock appendDelta(String delta) {
        return new FeishuStreamingReplyCardBlock(blockId, blockType, content + (delta == null ? "" : delta), false);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feishu streaming reply card block " + field + " is required");
        }
        return value;
    }
}
