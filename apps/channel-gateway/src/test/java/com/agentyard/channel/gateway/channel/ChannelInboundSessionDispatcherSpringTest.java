package com.agentyard.channel.gateway.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChannelInboundSessionDispatcherSpringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ChannelAdminRepository.class, () -> mock(ChannelAdminRepository.class))
        .withBean(ChannelSessionRuntimeClient.class, () -> mock(ChannelSessionRuntimeClient.class))
        .withBean(
            ChannelBindingSnapshotRefreshHintClient.class,
            () -> mock(ChannelBindingSnapshotRefreshHintClient.class)
        )
        .withUserConfiguration(ChannelInboundSessionDispatcher.class);

    @Test
    void createsDispatcherWithSpringConstructorInjection() {
        contextRunner.run(context ->
            assertThat(context).hasSingleBean(ChannelInboundSessionDispatcher.class)
        );
    }
}
