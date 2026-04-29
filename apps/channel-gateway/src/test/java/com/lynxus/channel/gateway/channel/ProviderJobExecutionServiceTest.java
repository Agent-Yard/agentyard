package com.lynxus.channel.gateway.channel;

import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB_RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.lynxus.channel.gateway.extension.ChannelGatewayDescriptorProvider;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoader;
import com.lynxus.channel.gateway.extension.ExtensionManifestFetcher;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.extension.RuntimeChannelProviderRegistry;
import com.lynxus.channel.gateway.jooqsupport.JooqJsonbSupport;
import com.lynxus.channel.gateway.jooqsupport.JooqTimeSupport;
import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRunStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleType;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleWriteConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobStatus;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.extension.sdk.common.LynxusCanonicalJson;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class ProviderJobExecutionServiceTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminService adminService;
    private ChannelAdminRepository repository;
    private FakeProviderJobLockService lockService;
    private FakeExecutor executor;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new ChannelAdminRepository(database.dsl(), objectMapper);
        adminService = new ChannelAdminService(repository, registry());
        lockService = new FakeProviderJobLockService();
        executor = new FakeExecutor();
    }

    @Test
    void manualRunClaimsExecutesAndRestoresActive() {
        ChannelGatewayProfile profile = createProfile();
        ChannelProviderJobConfig job = createActiveJob(profile);
        executor.result = new ProviderJobExecutionResult(0, "cursor-2", Map.of("providerStatus", "ok"));

        var run = service().runManual(profile.id(), "PULL_MESSAGES");

        assertEquals(ChannelProviderJobRunStatus.SUCCEEDED, run.status());
        assertEquals("cursor-2", run.nextCursor());
        assertEquals("channel-provider-job:" + job.jobId(), lockService.lastKey);
        ChannelProviderJobConfig updated = repository.findJob(profile.id(), "PULL_MESSAGES").orElseThrow();
        assertEquals(ChannelProviderJobStatus.ACTIVE, updated.status());
        assertNotNull(updated.nextRunAt());
        assertNotNull(updated.lastSuccessAt());
    }

    @Test
    void scannerRunsDueActiveJobs() {
        ChannelGatewayProfile profile = createProfile();
        ChannelProviderJobConfig job = createActiveJob(profile);
        database.dsl().update(CHANNEL_PROFILE_JOB)
            .set(CHANNEL_PROFILE_JOB.NEXT_RUN_AT, JooqTimeSupport.toOffsetDateTime(Instant.parse("2026-04-25T00:00:00Z")))
            .where(CHANNEL_PROFILE_JOB.ID.eq(job.jobId()))
            .execute();

        service().scanDueJobs();

        assertEquals(1, repository.listJobRuns(job.jobId()).size());
        assertEquals(ChannelProviderJobRunStatus.SUCCEEDED, repository.listJobRuns(job.jobId()).getFirst().status());
    }

    @Test
    void manualRunLockContentionReturnsConflictWithoutRunRow() {
        ChannelGatewayProfile profile = createProfile();
        ChannelProviderJobConfig job = createActiveJob(profile);
        lockService.acquireResult = false;

        assertThrows(ConflictException.class, () -> service().runManual(profile.id(), "PULL_MESSAGES"));

        assertTrue(repository.listJobRuns(job.jobId()).isEmpty());
    }

    @Test
    void disabledOrPausedJobsDoNotCreateManualRunRows() {
        ChannelGatewayProfile profile = createProfile();
        ChannelProviderJobConfig disabled = adminService.upsertJob(profile.id(), "PULL_MESSAGES", new ChannelProviderJobConfigWriteRequest(
            new ChannelProviderJobScheduleWriteConfig(
                false,
                ChannelProviderJobScheduleType.INTERVAL,
                30,
                null,
                null,
                45,
                Map.of("cursorMode", "incremental")
            ),
            null
        ));

        ConflictException error = assertThrows(ConflictException.class, () -> service().runManual(profile.id(), "PULL_MESSAGES"));

        assertEquals("CHANNEL_PROVIDER_JOB_NOT_ACTIVE", error.getMessage());
        assertTrue(repository.listJobRuns(disabled.jobId()).isEmpty());
    }

    @Test
    void failureStoresSanitizedErrorAndSchedulesNextRunWithoutImmediateRetry() {
        ChannelGatewayProfile profile = createProfile();
        ChannelProviderJobConfig job = createActiveJob(profile);
        executor.error = new IllegalStateException("externalSecretRef=vault://secret-ref failed");

        var run = service().runManual(profile.id(), "PULL_MESSAGES");

        assertEquals(ChannelProviderJobRunStatus.FAILED, run.status());
        ChannelProviderJobConfig failed = repository.findJob(profile.id(), "PULL_MESSAGES").orElseThrow();
        assertEquals(1, failed.failureCount());
        assertFalse(failed.lastError().contains("vault://secret-ref"));
        assertTrue(failed.nextRunAt().isAfter(failed.lastRunAt()));
        assertEquals(1, repository.listJobRuns(job.jobId()).size());
    }

    @Test
    void staleRunningRecoveryTimesOutRunAndLateCompletionDoesNotOverwriteTerminalRun() {
        ChannelGatewayProfile profile = createProfile();
        ChannelProviderJobConfig job = createActiveJob(profile);
        Instant startedAt = Instant.parse("2026-04-25T00:00:00Z");
        String runId = "channel-job-run-stale";
        repository.claimJob(job.jobId(), runId, "channel-job-run:" + runId, true, startedAt);
        lockService.locks.clear();

        service().recoverStaleRunningJobs(startedAt.plusSeconds(90));
        boolean lateWrite = repository.completeRunSucceeded(
            job.jobId(),
            runId,
            new ProviderJobExecutionResult(0, "late-cursor", Map.of()),
            startedAt.plusSeconds(91)
        );

        assertFalse(lateWrite);
        var run = repository.listJobRuns(job.jobId()).getFirst();
        assertEquals(ChannelProviderJobRunStatus.TIMED_OUT, run.status());
        ChannelProviderJobConfig recovered = repository.findJob(profile.id(), "PULL_MESSAGES").orElseThrow();
        assertEquals(ChannelProviderJobStatus.ACTIVE, recovered.status());
        assertEquals(1, recovered.failureCount());
    }

    private ProviderJobExecutionService service() {
        return new ProviderJobExecutionService(repository, lockService, executor, 50);
    }

    private ChannelGatewayProfile createProfile() {
        return adminService.createProfile(new CreateChannelProfileInternalRequest(
            "enterprise.acme.jobs",
            "Acme Jobs",
            null,
            true,
            Map.of(),
            null,
            new ChannelProfileAccountSnapshot("integration-account-1", "vault://secret-ref")
        ));
    }

    private ChannelProviderJobConfig createActiveJob(ChannelGatewayProfile profile) {
        return adminService.upsertJob(profile.id(), "PULL_MESSAGES", new ChannelProviderJobConfigWriteRequest(null, null));
    }

    private static RuntimeChannelProviderRegistry registry() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor()));
        return new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
            registrationServiceFromYaml("""
                lynxus:
                  extensions:
                    services:
                      - registrationId: acme-channel-provider
                        baseUrl: http://channel.example.com
                        exposes:
                          channelProviderTypes:
                            - enterprise.acme.jobs
                        auth:
                          type: INTERNAL_TOKEN
                """),
            new ChannelGatewayDescriptorProvider(),
            fetcher,
            "internal-token"
        ));
    }

    private static ExtensionRegistrationService registrationServiceFromYaml(String operatorYaml) {
        try {
            Path tempFile = Files.createTempFile("lynxus-extension-registration", ".yaml");
            Files.writeString(tempFile, operatorYaml);
            return new ExtensionRegistrationService(new ExtensionRegistrationProperties(
                tempFile.toString(),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            ));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String manifest(Map<String, Object> channelDescriptor) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", List.of(channelDescriptor));
        descriptors.put("toolConnectors", List.of());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return LynxusCanonicalJson.canonicalizeValue(manifest);
    }

    private static Map<String, Object> channelDescriptor() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("sendOutbound", "/channel/send-outbound");
        endpoints.put("runJob", "/channel/run-job");
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", "enterprise.acme.jobs");
        descriptor.put("title", "Acme Jobs Provider");
        descriptor.put("accountConfigSchema", Map.of());
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of());
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of(jobDefinition()));
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> jobDefinition() {
        Map<String, Object> defaultSchedule = new LinkedHashMap<>();
        defaultSchedule.put("scheduleType", "INTERVAL");
        defaultSchedule.put("intervalSeconds", 30);
        defaultSchedule.put("timezone", "UTC");
        defaultSchedule.put("jobConfig", Map.of("cursorMode", "incremental"));

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("jobType", "PULL_MESSAGES");
        job.put("title", "Pull messages");
        job.put("jobConfigSchema", objectSchema(Map.of("cursorMode", Map.of("type", "string")), List.of("cursorMode")));
        job.put("jobConfigUiSchema", List.of());
        job.put("defaultSchedule", defaultSchedule);
        job.put("defaultEnabled", true);
        job.put("defaultJobTimeoutSeconds", 45);
        return job;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static final class CapturingFetcher implements ExtensionManifestFetcher {
        private final Map<String, String> responses = new LinkedHashMap<>();

        @Override
        public String fetch(String manifestUrl, Map<String, String> headers) throws IOException {
            return responses.get(headers.get(LynxusExtensionHeaders.REGISTRATION_ID));
        }
    }

    private static final class FakeExecutor implements ProviderJobExecutor {
        ProviderJobExecutionResult result = ProviderJobExecutionResult.empty();
        Exception error;

        @Override
        public ProviderJobExecutionResult run(ProviderJobClaim claim) throws Exception {
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private static final class FakeProviderJobLockService extends ProviderJobLockService {
        final Map<String, String> locks = new ConcurrentHashMap<>();
        boolean acquireResult = true;
        String lastKey;

        FakeProviderJobLockService() {
            super(mock(StringRedisTemplate.class));
        }

        @Override
        public boolean acquire(String jobId, String runId, Duration ttl) {
            lastKey = key(jobId);
            if (!acquireResult) {
                return false;
            }
            return locks.putIfAbsent(jobId, runId) == null;
        }

        @Override
        public boolean owns(String jobId, String runId) {
            return runId.equals(locks.get(jobId));
        }

        @Override
        public boolean exists(String jobId) {
            return locks.containsKey(jobId);
        }

        @Override
        public void release(String jobId, String runId) {
            locks.remove(jobId, runId);
        }
    }
}
