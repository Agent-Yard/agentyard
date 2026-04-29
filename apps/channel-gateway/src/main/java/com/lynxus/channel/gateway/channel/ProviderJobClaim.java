package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import java.time.Instant;
import java.util.Map;

record ProviderJobClaim(
    String jobId,
    String runId,
    String idempotencyKey,
    String channelProfileId,
    String providerType,
    Map<String, Object> profileConfig,
    String externalSecretRef,
    String jobType,
    ChannelProviderJobScheduleConfig scheduleConfig,
    String cursor,
    Instant scheduledAt,
    Instant startedAt,
    int jobTimeoutSeconds
) {
}
