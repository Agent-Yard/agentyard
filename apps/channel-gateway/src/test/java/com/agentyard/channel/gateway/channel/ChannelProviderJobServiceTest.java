package com.agentyard.channel.gateway.channel;

import static com.agentyard.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB;
import static com.agentyard.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB_RUN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.agentyard.channel.gateway.extension.ChannelGatewayDescriptorProvider;
import com.agentyard.channel.gateway.extension.ChannelProviderRegistryLoader;
import com.agentyard.channel.gateway.extension.ExtensionManifestFetcher;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationProperties;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationService;
import com.agentyard.channel.gateway.extension.RuntimeChannelProviderRegistry;
import com.agentyard.channel.gateway.jooqsupport.JooqJsonbSupport;
import com.agentyard.channel.gateway.jooqsupport.JooqTimeSupport;
import com.agentyard.channel.gateway.shared.ConflictException;
import com.agentyard.channel.gateway.shared.UnprocessableEntityException;
import com.agentyard.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobRunStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobScheduleType;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobScheduleWriteConfig;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProviderJobStatus;
import com.agentyard.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.agentyard.extension.sdk.common.AgentYardCanonicalJson;
import com.agentyard.extension.sdk.protocol.AgentYardExtensionHeaders;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelProviderJobServiceTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminService service;
    private JooqJsonbSupport jsonbSupport;

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
        jsonbSupport = new JooqJsonbSupport(objectMapper);
        service = new ChannelAdminService(new ChannelAdminRepository(database.dsl(), objectMapper), registry());
    }

    @Test
    void createsJobFromDescriptorDefaultsAndDoesNotPersistEnabled() {
        ChannelGatewayProfile profile = createJobProfile();

        ChannelProviderJobConfig job = service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(null, null)
        );

        assertEquals("PULL_MESSAGES", job.jobType());
        assertEquals(ChannelProviderJobStatus.ACTIVE, job.status());
        assertEquals(ChannelProviderJobScheduleType.INTERVAL, job.scheduleConfig().scheduleType());
        assertEquals(30, job.scheduleConfig().intervalSeconds());
        assertEquals("UTC", job.scheduleConfig().timezone());
        assertEquals(45, job.scheduleConfig().jobTimeoutSeconds());
        assertEquals(Map.of("cursorMode", "incremental"), job.scheduleConfig().jobConfig());
        assertNotNull(job.nextRunAt());
        assertEquals(1, job.revision());

        Map<String, Object> persistedSchedule = jsonbSupport.readObjectMap(database.dsl()
            .select(CHANNEL_PROFILE_JOB.SCHEDULE_CONFIG)
            .from(CHANNEL_PROFILE_JOB)
            .where(CHANNEL_PROFILE_JOB.ID.eq(job.jobId()))
            .fetchSingle(CHANNEL_PROFILE_JOB.SCHEDULE_CONFIG));
        assertFalse(persistedSchedule.containsKey("enabled"));
    }

    @Test
    void rejectsUnknownJobTypeAndProvidersWithoutJobDefinitions() {
        ChannelGatewayProfile profile = service.createProfile(new CreateChannelProfileInternalRequest(
            "feishu",
            "Feishu",
            null,
            true,
            Map.of(),
            null,
            null
        ));

        assertThrows(UnprocessableEntityException.class, () -> service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(null, null)
        ));
    }

    @Test
    void validatesScheduleAndRejectsSecretMarkersInJobConfig() {
        ChannelGatewayProfile profile = createJobProfile();

        UnprocessableEntityException missingEnabled = assertThrows(UnprocessableEntityException.class, () -> service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(new ChannelProviderJobScheduleWriteConfig(
                null,
                ChannelProviderJobScheduleType.INTERVAL,
                60,
                null,
                null,
                null,
                Map.of("cursorMode", "incremental")
            ), null)
        ));
        assertEquals("scheduleConfig.enabled is required", missingEnabled.getMessage());

        assertThrows(UnprocessableEntityException.class, () -> service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(new ChannelProviderJobScheduleWriteConfig(
                true,
                ChannelProviderJobScheduleType.INTERVAL,
                null,
                null,
                null,
                null,
                Map.of()
            ), null)
        ));

        UnprocessableEntityException secretError = assertThrows(UnprocessableEntityException.class, () -> service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(new ChannelProviderJobScheduleWriteConfig(
                true,
                ChannelProviderJobScheduleType.INTERVAL,
                60,
                null,
                null,
                null,
                Map.of("apiKey", "secret")
            ), null)
        ));
        assertEquals("scheduleConfig.jobConfig.apiKey must not contain secret material", secretError.getMessage());
    }

    @Test
    void rejectsUpdateAndDeleteWhenJobIsRunning() {
        ChannelGatewayProfile profile = createJobProfile();
        ChannelProviderJobConfig job = service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(null, null)
        );
        database.dsl().update(CHANNEL_PROFILE_JOB)
            .set(CHANNEL_PROFILE_JOB.STATUS, ChannelProviderJobStatus.RUNNING.name())
            .where(CHANNEL_PROFILE_JOB.ID.eq(job.jobId()))
            .execute();

        assertThrows(ConflictException.class, () -> service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(null, 1L)
        ));
        assertThrows(ConflictException.class, () -> service.deleteJob(profile.id(), "PULL_MESSAGES", 1L));
    }

    @Test
    void disablesJobWithoutDroppingRunHistory() {
        ChannelGatewayProfile profile = createJobProfile();
        ChannelProviderJobConfig job = service.upsertJob(
            profile.id(),
            "PULL_MESSAGES",
            new ChannelProviderJobConfigWriteRequest(null, null)
        );
        Instant now = Instant.parse("2026-04-25T00:00:00Z");
        database.dsl().insertInto(CHANNEL_PROFILE_JOB_RUN)
            .set(CHANNEL_PROFILE_JOB_RUN.ID, "run-1")
            .set(CHANNEL_PROFILE_JOB_RUN.JOB_ID, job.jobId())
            .set(CHANNEL_PROFILE_JOB_RUN.STATUS, ChannelProviderJobRunStatus.SUCCEEDED.name())
            .set(CHANNEL_PROFILE_JOB_RUN.SCHEDULED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .set(CHANNEL_PROFILE_JOB_RUN.STARTED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .set(CHANNEL_PROFILE_JOB_RUN.JOB_TIMEOUT_SECONDS, 45)
            .set(CHANNEL_PROFILE_JOB_RUN.FINISHED_AT, JooqTimeSupport.toOffsetDateTime(now.plusSeconds(2)))
            .set(CHANNEL_PROFILE_JOB_RUN.DURATION_MS, 2000L)
            .set(CHANNEL_PROFILE_JOB_RUN.IDEMPOTENCY_KEY, "job:run-1")
            .set(CHANNEL_PROFILE_JOB_RUN.ATTEMPT, 1)
            .set(CHANNEL_PROFILE_JOB_RUN.EVENTS_INGESTED, 3)
            .set(CHANNEL_PROFILE_JOB_RUN.ERROR, jsonbSupport.toJsonb(Map.of()))
            .set(CHANNEL_PROFILE_JOB_RUN.METADATA, jsonbSupport.toJsonb(Map.of("source", "test")))
            .set(CHANNEL_PROFILE_JOB_RUN.CREATED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .set(CHANNEL_PROFILE_JOB_RUN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now.plusSeconds(2)))
            .execute();

        ChannelProviderJobConfig disabled = service.deleteJob(profile.id(), "PULL_MESSAGES", 1L);

        assertEquals(ChannelProviderJobStatus.DISABLED, disabled.status());
        assertNull(disabled.nextRunAt());
        assertEquals(2, disabled.revision());
        assertEquals(1, service.listJobRuns(profile.id(), "PULL_MESSAGES").size());
        assertEquals(3, service.listJobRuns(profile.id(), "PULL_MESSAGES").getFirst().eventsIngested());
    }

    private ChannelGatewayProfile createJobProfile() {
        return service.createProfile(new CreateChannelProfileInternalRequest(
            "enterprise.acme.jobs",
            "Acme Jobs",
            null,
            true,
            Map.of(),
            null,
            null
        ));
    }

    private static RuntimeChannelProviderRegistry registry() {
        CapturingFetcher fetcher = new CapturingFetcher();
        fetcher.responses.put("acme-channel-provider", manifest(channelDescriptor()));
        return new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
            registrationServiceFromYaml("""
                agentyard:
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
            Path tempFile = Files.createTempFile("agentyard-extension-registration", ".yaml");
            Files.writeString(tempFile, operatorYaml);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(tempFile.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
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
        return AgentYardCanonicalJson.canonicalizeValue(manifest);
    }

    private static Map<String, Object> channelDescriptor() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
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
        descriptor.put("outbound", outbound());
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> outbound() {
        return Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", false,
            "supportsDraftUpdate", false,
            "supportsFinalDelivery", true,
            "requiresIdempotentFinalDelivery", true
        );
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
        job.put("jobConfigSchema", objectSchema(
            Map.of("cursorMode", Map.of("type", "string")),
            List.of("cursorMode")
        ));
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
            String registrationId = headers.get(AgentYardExtensionHeaders.REGISTRATION_ID);
            if (!responses.containsKey(registrationId)) {
                throw new AssertionError("unexpected manifest fetch for " + registrationId);
            }
            return responses.get(registrationId);
        }
    }
}
