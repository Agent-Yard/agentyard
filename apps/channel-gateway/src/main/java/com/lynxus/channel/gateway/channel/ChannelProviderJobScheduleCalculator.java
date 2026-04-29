package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

final class ChannelProviderJobScheduleCalculator {
    private ChannelProviderJobScheduleCalculator() {
    }

    static Instant nextRunAt(ChannelProviderJobScheduleConfig scheduleConfig, Instant now) {
        return switch (scheduleConfig.scheduleType()) {
            case INTERVAL -> now.plusSeconds(scheduleConfig.intervalSeconds());
            case CRON -> {
                ZonedDateTime base = now.atZone(ZoneId.of(scheduleConfig.timezone()));
                ZonedDateTime next = CronExpression.parse(scheduleConfig.cronExpression()).next(base);
                yield next == null ? null : next.toInstant();
            }
            case MANUAL -> null;
        };
    }
}
