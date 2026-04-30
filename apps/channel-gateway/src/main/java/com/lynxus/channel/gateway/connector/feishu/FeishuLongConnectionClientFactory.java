package com.lynxus.channel.gateway.connector.feishu;

interface FeishuLongConnectionClientFactory {
    FeishuLongConnectionClient create(FeishuLongConnectionProfileResolver profileResolver, FeishuAppCredential credential);
}
