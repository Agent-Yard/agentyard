package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeishuIntegrationCredentialProviderTest {
    @Test
    void loadsAppCredentialFromIntegrationAccountConfigAndCredential() {
        FeishuIntegrationCredentialProvider provider = new FeishuIntegrationCredentialProvider(accountId ->
            new FeishuIntegrationAccountRuntime(
                accountId,
                "CHANNEL_PROVIDER",
                "feishu",
                "ENABLED",
                Map.of("appId", "cli_test"),
                Map.of("appSecret", "secret_test")
            )
        );

        FeishuAppCredential credential = provider.resolve("account-1");

        assertEquals("account-1", credential.accountId());
        assertEquals("cli_test", credential.appId());
        assertEquals("secret_test", credential.appSecret());
    }
}
