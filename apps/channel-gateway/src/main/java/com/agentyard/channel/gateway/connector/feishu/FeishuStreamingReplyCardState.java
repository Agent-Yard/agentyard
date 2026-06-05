package com.agentyard.channel.gateway.connector.feishu;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

record FeishuStreamingReplyCardState(
    FeishuStreamingReplyCardKey key,
    String externalConversationId,
    String cardId,
    String externalMessageId,
    String elementId,
    List<FeishuStreamingReplyCardBlock> blocks,
    int sequence,
    Long lastSourceSeq,
    boolean closed,
    Instant updatedAt
) {
    FeishuStreamingReplyCardState {
        if (key == null) {
            throw new IllegalArgumentException("Feishu streaming reply card key is required");
        }
        externalConversationId = requireText(externalConversationId, "externalConversationId");
        cardId = requireText(cardId, "cardId");
        elementId = requireText(elementId, "elementId");
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        if (sequence < 0) {
            throw new IllegalArgumentException("Feishu streaming reply card sequence must be non-negative");
        }
        if (lastSourceSeq != null && lastSourceSeq <= 0) {
            throw new IllegalArgumentException("Feishu streaming reply card lastSourceSeq must be positive");
        }
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    String content() {
        return FeishuReplyMarkdownRenderer.render(blocks);
    }

    FeishuStreamingReplyCardState withDraftDelta(
        String blockId,
        String blockType,
        String delta,
        int sequence,
        Long lastSourceSeq,
        Instant updatedAt
    ) {
        FeishuStreamingReplyCardBlock candidate = new FeishuStreamingReplyCardBlock(blockId, blockType, "", false)
            .appendDelta(delta);
        List<FeishuStreamingReplyCardBlock> nextBlocks = new ArrayList<>();
        boolean replaced = false;
        for (FeishuStreamingReplyCardBlock block : blocks) {
            if (block.blockId().equals(candidate.blockId())) {
                nextBlocks.add(block.appendDelta(delta));
                replaced = true;
            } else {
                nextBlocks.add(block);
            }
        }
        if (!replaced) {
            nextBlocks.add(candidate);
        }
        return withBlocks(nextBlocks, sequence, lastSourceSeq, updatedAt);
    }

    FeishuStreamingReplyCardState withCompletedBlock(
        FeishuStreamingReplyCardBlock completedBlock,
        int sequence,
        Long lastSourceSeq,
        Instant updatedAt
    ) {
        List<FeishuStreamingReplyCardBlock> nextBlocks = upsert(completedBlock);
        return withBlocks(nextBlocks, sequence, lastSourceSeq, updatedAt);
    }

    FeishuStreamingReplyCardState withBlocks(
        List<FeishuStreamingReplyCardBlock> blocks,
        int sequence,
        Long lastSourceSeq,
        Instant updatedAt
    ) {
        return new FeishuStreamingReplyCardState(
            key,
            externalConversationId,
            cardId,
            externalMessageId,
            elementId,
            blocks,
            sequence,
            lastSourceSeq,
            closed,
            updatedAt
        );
    }

    FeishuStreamingReplyCardState closed(int sequence, Long lastSourceSeq, Instant updatedAt) {
        return new FeishuStreamingReplyCardState(
            key,
            externalConversationId,
            cardId,
            externalMessageId,
            elementId,
            blocks,
            sequence,
            lastSourceSeq,
            true,
            updatedAt
        );
    }

    private List<FeishuStreamingReplyCardBlock> upsert(FeishuStreamingReplyCardBlock candidate) {
        List<FeishuStreamingReplyCardBlock> nextBlocks = new ArrayList<>();
        boolean replaced = false;
        for (FeishuStreamingReplyCardBlock block : blocks) {
            if (block.blockId().equals(candidate.blockId())) {
                nextBlocks.add(candidate);
                replaced = true;
            } else {
                nextBlocks.add(block);
            }
        }
        if (!replaced) {
            nextBlocks.add(candidate);
        }
        return nextBlocks;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Feishu streaming reply card " + field + " is required");
        }
        return value;
    }
}
