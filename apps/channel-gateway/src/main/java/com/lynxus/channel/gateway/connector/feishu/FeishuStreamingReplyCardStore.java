package com.lynxus.channel.gateway.connector.feishu;

import java.util.Optional;

interface FeishuStreamingReplyCardStore {
    Optional<FeishuStreamingReplyCardState> find(FeishuStreamingReplyCardKey key);

    void save(FeishuStreamingReplyCardState state);

    void delete(FeishuStreamingReplyCardKey key);
}
