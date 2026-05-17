package com.lynxus.channel.gateway.connector.feishu;

import java.util.List;

record FeishuRenderedReplyBlocks(
    String markdown,
    List<FeishuStreamingReplyCardBlock> blocks
) {
    FeishuRenderedReplyBlocks {
        markdown = markdown == null ? "" : markdown;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
