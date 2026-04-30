package com.lynxus.channel.gateway.channel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ChannelGatewayAsyncConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
        .withUserConfiguration(ChannelGatewayAsyncConfiguration.class);

    @Test
    void createsNamedLifecycleManagedInboundExecutorWithConfigurableBounds() {
        contextRunner
            .withPropertyValues(
                "lynxus.channel-gateway.async.inbound-session-dispatch.core-size=3",
                "lynxus.channel-gateway.async.inbound-session-dispatch.max-size=11",
                "lynxus.channel-gateway.async.inbound-session-dispatch.queue-capacity=250",
                "lynxus.channel-gateway.async.inbound-session-dispatch.keep-alive-seconds=45"
            )
            .run(context -> {
                ThreadPoolTaskExecutor inboundExecutor = context.getBean(
                    ChannelGatewayAsyncConfiguration.CHANNEL_INBOUND_SESSION_DISPATCH_EXECUTOR,
                    ThreadPoolTaskExecutor.class
                );

                assertThat(inboundExecutor.getCorePoolSize()).isEqualTo(3);
                assertThat(inboundExecutor.getMaxPoolSize()).isEqualTo(11);
                assertThat(inboundExecutor.getKeepAliveSeconds()).isEqualTo(45);
                assertThat(inboundExecutor.getThreadNamePrefix()).isEqualTo("channel-inbound-dispatch-");
            });
    }
}
