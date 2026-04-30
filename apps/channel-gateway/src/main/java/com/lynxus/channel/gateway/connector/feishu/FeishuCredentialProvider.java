package com.lynxus.channel.gateway.connector.feishu;

import java.util.Map;

public interface FeishuCredentialProvider {
    FeishuAppCredential resolve(String accountId, Map<String, Object> profileConfig);
}
