package com.lynxus.channel.gateway.connector.feishu;

interface FeishuLongConnectionClientFactory {
    FeishuLongConnectionClient create(FeishuLongConnectionProfile profile, FeishuAppCredential credential);
}
