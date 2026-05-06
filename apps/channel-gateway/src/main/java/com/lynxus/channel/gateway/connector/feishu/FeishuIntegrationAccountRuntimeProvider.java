package com.lynxus.channel.gateway.connector.feishu;

interface FeishuIntegrationAccountRuntimeProvider {
    FeishuIntegrationAccountRuntime load(String accountId);
}
