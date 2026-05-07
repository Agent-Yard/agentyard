package com.lynxus.channel.gateway.connector.feishu;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lynxus.channel-gateway.feishu")
public class FeishuGatewayProperties {
    private Duration credentialCacheTtl = Duration.ofMinutes(2);

    public Duration getCredentialCacheTtl() {
        if (credentialCacheTtl == null || credentialCacheTtl.isNegative()) {
            return Duration.ZERO;
        }
        return credentialCacheTtl;
    }

    public void setCredentialCacheTtl(Duration credentialCacheTtl) {
        this.credentialCacheTtl = credentialCacheTtl;
    }
}
