package com.lynxus.channel.gateway.connector.feishu;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class FeishuIntegrationCredentialProvider implements FeishuCredentialProvider {
    private final FeishuIntegrationAccountRuntimeProvider runtimeProvider;

    @Autowired
    FeishuIntegrationCredentialProvider(FeishuIntegrationAccountRuntimeProvider runtimeProvider) {
        this.runtimeProvider = runtimeProvider;
    }

    @Override
    public FeishuAppCredential resolve(String accountId) {
        String normalizedAccountId = requireText(accountId, "channel profile integration account");
        FeishuIntegrationAccountRuntime runtime = runtimeProvider.load(normalizedAccountId);
        runtime.requireEnabledFeishuChannelProvider();
        return new FeishuAppCredential(normalizedAccountId, runtime.appId(), runtime.appSecret());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
