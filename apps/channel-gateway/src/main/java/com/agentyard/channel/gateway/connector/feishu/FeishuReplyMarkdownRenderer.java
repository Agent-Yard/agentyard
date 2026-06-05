package com.agentyard.channel.gateway.connector.feishu;

import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FeishuReplyMarkdownRenderer {
    private static final Logger log = LoggerFactory.getLogger(FeishuReplyMarkdownRenderer.class);

    private FeishuReplyMarkdownRenderer() {
    }

    static String render(List<FeishuStreamingReplyCardBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        return blocks.stream()
            .filter(block -> block != null && isRenderableType(block.blockType()) && !block.content().isBlank())
            .map(FeishuStreamingReplyCardBlock::content)
            .reduce((first, second) -> first + "\n\n" + second)
            .orElse("");
    }

    static Optional<FeishuStreamingReplyCardBlock> completedBlock(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        String blockId,
        Object rawBlock
    ) {
        return blockFromRaw(profile, frame, rawBlock, blockId, 0, true);
    }

    static Optional<FeishuRenderedReplyBlocks> finalDeliveryBlocks(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame
    ) {
        Object rawBlocks = frame.payload().get("messageBlocks");
        if (!(rawBlocks instanceof List<?> blocks) || blocks.isEmpty()) {
            log.info(
                "skipping Feishu native final delivery with no renderable message blocks: channelProfileId={}, frameId={}, sessionId={}, sessionMessageId={}",
                frame.channelProfileId(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId")
            );
            return Optional.empty();
        }
        List<FeishuStreamingReplyCardBlock> renderedBlocks = new ArrayList<>();
        int index = 0;
        for (Object rawBlock : blocks) {
            int blockIndex = index++;
            String blockId = blockId(rawBlock).orElse("final-block-" + (blockIndex + 1));
            blockFromRaw(profile, frame, rawBlock, blockId, blockIndex, true)
                .ifPresent(renderedBlocks::add);
        }
        String markdown = render(renderedBlocks);
        if (markdown.isEmpty()) {
            log.info(
                "skipping Feishu native final delivery with no text content: channelProfileId={}, providerType={}, frameId={}, sessionId={}, sessionMessageId={}",
                profile.id(),
                profile.providerType(),
                frame.frameId(),
                frame.sessionId(),
                frame.payload().get("sessionMessageId")
            );
            return Optional.empty();
        }
        return Optional.of(new FeishuRenderedReplyBlocks(markdown, renderedBlocks));
    }

    private static Optional<FeishuStreamingReplyCardBlock> blockFromRaw(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        Object rawBlock,
        String blockId,
        int blockIndex,
        boolean completed
    ) {
        if (!(rawBlock instanceof Map<?, ?> block)) {
            logUnsupported(profile, frame, blockIndex, "UNKNOWN");
            return Optional.empty();
        }
        String type = blockType(block);
        if ("TEXT".equals(type)) {
            return Optional.of(new FeishuStreamingReplyCardBlock(blockId, type, stringValue(block.get("text")), completed));
        }
        if ("RICH_TEXT".equals(type)) {
            return Optional.of(new FeishuStreamingReplyCardBlock(blockId, type, stringValue(block.get("content")), completed));
        }
        logUnsupported(profile, frame, blockIndex, type);
        return Optional.empty();
    }

    private static Optional<String> blockId(Object rawBlock) {
        if (rawBlock instanceof Map<?, ?> block) {
            Object value = block.get("blockId");
            if (value instanceof String text && !text.isBlank()) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }

    private static String blockType(Map<?, ?> block) {
        Object rawType = block.get("type");
        return rawType == null ? "TEXT" : String.valueOf(rawType);
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }

    private static boolean isRenderableType(String blockType) {
        return "TEXT".equals(blockType) || "RICH_TEXT".equals(blockType);
    }

    private static void logUnsupported(
        ChannelGatewayProfile profile,
        ChannelOutboundFrame frame,
        int blockIndex,
        String blockType
    ) {
        log.info(
            "skipping unsupported Feishu native reply block: channelProfileId={}, providerType={}, frameId={}, sessionId={}, sessionMessageId={}, blockIndex={}, blockType={}",
            profile.id(),
            profile.providerType(),
            frame.frameId(),
            frame.sessionId(),
            frame.payload().get("sessionMessageId"),
            blockIndex,
            blockType
        );
    }
}
