package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import java.time.Instant;

record ProviderJobRunningRun(
    String jobId,
    String runId,
    ChannelProviderJobScheduleConfig scheduleConfig,
    Instant startedAt,
    int jobTimeoutSeconds
) {
}
