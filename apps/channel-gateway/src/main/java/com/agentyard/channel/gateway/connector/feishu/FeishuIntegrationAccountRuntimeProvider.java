package com.agentyard.channel.gateway.connector.feishu;

interface FeishuIntegrationAccountRuntimeProvider {
    FeishuIntegrationAccountRuntime load(String accountId);
}
