package com.lynxus.channel.gateway.channel;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(ChannelGatewayAsyncProperties.class)
public class ChannelGatewayAsyncConfiguration {
    public static final String CHANNEL_INBOUND_SESSION_DISPATCH_EXECUTOR = "channelInboundSessionDispatchExecutor";

    @Bean(name = CHANNEL_INBOUND_SESSION_DISPATCH_EXECUTOR)
    ThreadPoolTaskExecutor channelInboundSessionDispatchExecutor(ChannelGatewayAsyncProperties properties) {
        return taskExecutor(
            "channel-inbound-dispatch-",
            properties.getInboundSessionDispatch()
        );
    }

    private static ThreadPoolTaskExecutor taskExecutor(
        String threadNamePrefix,
        ChannelGatewayAsyncProperties.ExecutorPool pool
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(pool.getCoreSize());
        executor.setMaxPoolSize(pool.getMaxSize());
        executor.setQueueCapacity(pool.getQueueCapacity());
        executor.setKeepAliveSeconds(pool.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(true);
        executor.setWaitForTasksToCompleteOnShutdown(pool.isWaitForTasksToCompleteOnShutdown());
        executor.setAwaitTerminationSeconds(pool.getShutdownAwaitSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
