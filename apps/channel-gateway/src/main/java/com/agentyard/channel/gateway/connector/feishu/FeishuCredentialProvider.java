package com.agentyard.channel.gateway.connector.feishu;

public interface FeishuCredentialProvider {
    FeishuAppCredential resolve(String accountId);
}
