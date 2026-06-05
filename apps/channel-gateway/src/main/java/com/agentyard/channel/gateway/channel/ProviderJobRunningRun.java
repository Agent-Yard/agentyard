package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import java.time.Instant;

record ProviderJobRunningRun(
    String jobId,
    String runId,
    ChannelProviderJobScheduleConfig scheduleConfig,
    Instant startedAt,
    int jobTimeoutSeconds
) {
}
