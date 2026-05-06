package com.lynxus.channel.gateway.connector.feishu;

public interface FeishuCredentialProvider {
    FeishuAppCredential resolve(String accountId);
}
