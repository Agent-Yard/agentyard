package com.agentyard.channel.gateway.connector.feishu;

import java.util.function.Supplier;

interface FeishuLongConnectionClientFactory {
    void start(Supplier<FeishuLongConnectionProfile> profileResolver, FeishuAppCredential credential);
}
