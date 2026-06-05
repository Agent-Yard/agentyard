package com.agentyard.worker.shared.redis;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentyard.shared-state")
public record WorkerSharedStateProperties(
    String instanceId
) {
    public WorkerSharedStateProperties {
        instanceId = normalizeInstanceId(instanceId);
    }

    private static String normalizeInstanceId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            String host = InetAddress.getLocalHost().getHostName();
            String runtime = ManagementFactory.getRuntimeMXBean().getName().replace('@', '-');
            return host + "-" + runtime;
        } catch (Exception ignored) {
            return "agentyard-worker-instance";
        }
    }
}
