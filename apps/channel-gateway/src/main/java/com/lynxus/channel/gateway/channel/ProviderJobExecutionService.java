package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRun;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ProviderJobExecutionService {
    private static final int DEFAULT_SCAN_LIMIT = 50;

    private final ChannelAdminRepository repository;
    private final ProviderJobLockService lockService;
    private final ProviderJobExecutor executor;
    private final int scanLimit;

    public ProviderJobExecutionService(
        ChannelAdminRepository repository,
        ProviderJobLockService lockService,
        ProviderJobExecutor executor,
        @Value("${lynxus.channel-gateway.provider-jobs.scan-limit:50}") int scanLimit
    ) {
        this.repository = repository;
        this.lockService = lockService;
        this.executor = executor;
        this.scanLimit = scanLimit < 1 ? DEFAULT_SCAN_LIMIT : scanLimit;
    }

    @Scheduled(fixedDelayString = "${lynxus.channel-gateway.provider-jobs.scan-fixed-delay-ms:30000}")
    public void scanDueJobs() {
        Instant now = Instant.now();
        recoverStaleRunningJobs(now);
        for (String jobId : repository.listDueActiveJobIds(now, scanLimit)) {
            execute(jobId, false, now);
        }
    }

    public ChannelProviderJobRun runManual(String channelProfileId, String jobType) {
        var job = repository.findJob(channelProfileId, jobType)
            .orElseThrow(() -> new java.util.NoSuchElementException("channel provider job not found: " + jobType));
        if (job.status() == ChannelProviderJobStatus.RUNNING) {
            throw new ConflictException("CHANNEL_PROVIDER_JOB_RUNNING");
        }
        if (job.status() != ChannelProviderJobStatus.ACTIVE) {
            throw new ConflictException("CHANNEL_PROVIDER_JOB_NOT_ACTIVE");
        }
        ProviderJobClaim claim = execute(job.jobId(), true, Instant.now())
            .orElseThrow(() -> new ConflictException("CHANNEL_PROVIDER_JOB_RUNNING"));
        return repository.listJobRuns(claim.jobId()).stream()
            .filter(run -> run.runId().equals(claim.runId()))
            .findFirst()
            .orElseThrow();
    }

    void recoverStaleRunningJobs(Instant now) {
        for (ProviderJobRunningRun running : repository.listRunningRuns()) {
            if (lockService.exists(running.jobId())) {
                continue;
            }
            Instant timeoutAt = running.startedAt().plusSeconds(running.jobTimeoutSeconds());
            if (!now.isBefore(timeoutAt)) {
                repository.recoverTimedOutRun(
                    running.jobId(),
                    running.runId(),
                    sanitizeError("provider job exceeded timeout " + running.jobTimeoutSeconds() + "s"),
                    now
                );
            }
        }
    }

    private Optional<ProviderJobClaim> execute(String jobId, boolean manual, Instant scheduledNow) {
        String runId = nextId("channel-job-run");
        String idempotencyKey = "channel-job-run:" + runId;
        int timeoutSeconds = repository.findJobById(jobId)
            .map(job -> job.scheduleConfig().jobTimeoutSeconds())
            .orElse(60);
        Duration ttl = Duration.ofSeconds(Math.max(timeoutSeconds * 2L, 60L));
        if (!lockService.acquire(jobId, runId, ttl)) {
            if (manual) {
                throw new ConflictException("CHANNEL_PROVIDER_JOB_RUNNING");
            }
            return Optional.empty();
        }
        ProviderJobClaim claim = null;
        try {
            Optional<ProviderJobClaim> claimed = repository.claimJob(jobId, runId, idempotencyKey, manual, scheduledNow);
            if (claimed.isEmpty()) {
                if (manual) {
                    throw new ConflictException("CHANNEL_PROVIDER_JOB_NOT_ACTIVE");
                }
                return Optional.empty();
            }
            claim = claimed.get();
            if (!lockService.owns(jobId, runId)) {
                return Optional.of(claim);
            }
            try {
                ProviderJobExecutionResult result = executor.run(claim);
                if (lockService.owns(jobId, runId)) {
                    repository.completeRunSucceeded(jobId, runId, result, Instant.now());
                }
            } catch (java.net.http.HttpTimeoutException timeout) {
                if (lockService.owns(jobId, runId)) {
                    repository.completeRunTimedOut(jobId, runId, sanitizeError(timeout), Instant.now());
                }
            } catch (Exception error) {
                if (lockService.owns(jobId, runId)) {
                    repository.completeRunFailed(jobId, runId, sanitizeError(error), Instant.now());
                }
            }
            return Optional.of(claim);
        } finally {
            lockService.release(jobId, runId);
        }
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String sanitizeError(Throwable error) {
        return sanitizeError(error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "" : error.getMessage()));
    }

    private static String sanitizeError(String message) {
        String sanitized = message == null ? "provider job failed" : message;
        sanitized = sanitized.replaceAll("(?i)externalSecretRef\\s*[:=]\\s*[^\\s,}]+", "externalSecretRef=[REDACTED]");
        sanitized = sanitized.replaceAll("vault://[^\\s\"']+", "[REDACTED]");
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }
}
