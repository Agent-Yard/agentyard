package com.agentyard.channel.gateway.connector.feishu;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
final class FeishuStreamingReplyCardReadiness {
    private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(10);

    private final ConcurrentHashMap<FeishuStreamingReplyCardKey, Monitor> monitors = new ConcurrentHashMap<>();
    private final Duration waitTimeout;

    FeishuStreamingReplyCardReadiness() {
        this(DEFAULT_WAIT_TIMEOUT);
    }

    FeishuStreamingReplyCardReadiness(Duration waitTimeout) {
        this.waitTimeout = waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()
            ? DEFAULT_WAIT_TIMEOUT
            : waitTimeout;
    }

    Reservation begin(FeishuStreamingReplyCardKey key) {
        Monitor monitor = monitors.computeIfAbsent(key, ignored -> new Monitor());
        synchronized (monitor) {
            while (monitor.preparing) {
                waitForNotification(monitor, waitTimeout);
            }
            monitor.preparing = true;
        }
        return new Reservation(key, monitor);
    }

    Optional<FeishuStreamingReplyCardState> awaitReady(
        FeishuStreamingReplyCardKey key,
        Supplier<Optional<FeishuStreamingReplyCardState>> stateLookup
    ) {
        Optional<FeishuStreamingReplyCardState> state = stateLookup.get();
        if (state.isPresent()) {
            return state;
        }
        Monitor monitor = monitors.get(key);
        if (monitor == null) {
            return state;
        }

        long deadline = System.nanoTime() + waitTimeout.toNanos();
        synchronized (monitor) {
            while (monitor.preparing) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return stateLookup.get();
                }
                waitForNotification(monitor, Duration.ofNanos(remaining));
                state = stateLookup.get();
                if (state.isPresent()) {
                    return state;
                }
            }
        }
        return stateLookup.get();
    }

    private static void waitForNotification(Monitor monitor, Duration timeout) {
        try {
            TimeUnit.NANOSECONDS.timedWait(monitor, timeout.toNanos());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for Feishu streaming reply card readiness", error);
        }
    }

    final class Reservation implements AutoCloseable {
        private final FeishuStreamingReplyCardKey key;
        private final Monitor monitor;
        private boolean closed;

        private Reservation(FeishuStreamingReplyCardKey key, Monitor monitor) {
            this.key = key;
            this.monitor = monitor;
        }

        @Override
        public void close() {
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                closed = true;
                monitor.preparing = false;
                monitor.notifyAll();
            }
            monitors.remove(key, monitor);
        }
    }

    private static final class Monitor {
        private boolean preparing;
    }
}
