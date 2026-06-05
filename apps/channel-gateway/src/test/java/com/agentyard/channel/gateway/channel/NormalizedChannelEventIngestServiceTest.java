package com.agentyard.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentyard.channel.gateway.extension.ExtensionRegistrationProperties;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationService;
import com.agentyard.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelEventType;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessage;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelSender;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NormalizedChannelEventIngestServiceTest {
    private static final String PROVIDER_TYPE = "enterprise.acme.internal-im";
    private static final String TICKET_PROVIDER_TYPE = "enterprise.acme.ticket";
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;
    private NormalizedChannelEventIngestService service;

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
        repository = new ChannelAdminRepository(database.dsl(), new ObjectMapper());
        service = new NormalizedChannelEventIngestService(repository, registrationService());
    }

    @Test
    void duplicateDedupKeyReturnsExistingNonMessageEvent() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent event = webhookVerifiedEvent("channel-profile-1", "enterprise.acme.internal-im:webhook:verified");
        NormalizedChannelInboundEventResult first = service.ingest(event, validHeaders(event.dedupKey()));
        NormalizedChannelInboundEventResult second = service.ingest(event, validHeaders(event.dedupKey()));

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.eventId(), second.eventId());
        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
        assertEquals(0, repository.listBindings("channel-profile-1").size());
    }

    @Test
    void duplicateDedupKeyStillValidatesRequestedProfileBeforeReturningExistingNonMessageEvent() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("inactive-profile", PROVIDER_TYPE, ChannelProfileStatus.INACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("disabled-profile", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, false, new ChannelAssistantBinding("assistant-1", null));
        createProfile("mismatch-profile", TICKET_PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent original = webhookVerifiedEvent("channel-profile-1", "enterprise.acme.internal-im:webhook:verified");
        service.ingest(original, validHeaders(original.dedupKey()));

        assertEquals(
            "channel profile is not ACTIVE: inactive-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEvent("inactive-profile", original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "channel profile inbound is disabled: disabled-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEvent("disabled-profile", original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "channel profile providerType does not match normalizedEvent.providerType",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEventWithProvider(PROVIDER_TYPE, "mismatch-profile", original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "channel profile not found: missing-profile",
            assertThrows(java.util.NoSuchElementException.class, () -> service.ingest(
                webhookVerifiedEvent("missing-profile", original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
    }

    @Test
    void duplicateDedupKeyRejectsDifferentNonMessageEventSurface() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("channel-profile-2", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("ticket-profile", TICKET_PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent original = webhookVerifiedEvent("channel-profile-1", "enterprise.acme.internal-im:webhook:verified");
        service.ingest(original, validHeaders(original.dedupKey()));

        assertEquals(
            "normalized event dedupKey already belongs to a different event surface",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEvent("channel-profile-2", original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "normalized event dedupKey already belongs to a different event surface",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEventWithProvider(TICKET_PROVIDER_TYPE, "ticket-profile", original.dedupKey()),
                validHeaders(TICKET_PROVIDER_TYPE, original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "normalized event dedupKey already belongs to a different event surface",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                eventWithConversation("channel-profile-1", NormalizedChannelEventType.MESSAGE_UPDATED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
    }

    @Test
    void webhookVerifiedStoresEventWithoutConversationBinding() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));

        service.ingest(new NormalizedChannelInboundEvent(
            PROVIDER_TYPE,
            "channel-profile-1",
            NormalizedChannelEventType.WEBHOOK_VERIFIED,
            "enterprise.acme.internal-im:webhook:verified",
            "evt-verified",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of("verified", true),
            Map.of(),
            traceContext(),
            Map.of()
        ), validHeaders("enterprise.acme.internal-im:webhook:verified"));

        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
        assertEquals(0, repository.listBindings("channel-profile-1").size());
    }

    @Test
    void rejectsProfileProviderMismatchInactiveAndInboundDisabled() {
        createProfile("inactive-profile", PROVIDER_TYPE, ChannelProfileStatus.INACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("disabled-profile", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, false, new ChannelAssistantBinding("assistant-1", null));
        createProfile("mismatch-profile", "enterprise.acme.ticket", ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));

        assertEquals(
            "channel profile is not ACTIVE: inactive-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEvent("inactive-profile", "enterprise.acme.internal-im:webhook:inactive"),
                validHeaders("enterprise.acme.internal-im:webhook:inactive")
            )).getMessage()
        );
        assertEquals(
            "channel profile inbound is disabled: disabled-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEvent("disabled-profile", "enterprise.acme.internal-im:webhook:disabled"),
                validHeaders("enterprise.acme.internal-im:webhook:disabled")
            )).getMessage()
        );
        assertEquals(
            "channel profile providerType does not match normalizedEvent.providerType",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                webhookVerifiedEvent("mismatch-profile", "enterprise.acme.internal-im:webhook:mismatch"),
                validHeaders("enterprise.acme.internal-im:webhook:mismatch")
            )).getMessage()
        );
    }

    @Test
    void rejectsRegistrationThatDoesNotExposeProvider() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.ingest(
            webhookVerifiedEvent("channel-profile-1", "enterprise.acme.internal-im:webhook:verified"),
            new NormalizedChannelEventHeaders(
                "missing-channel-provider",
                "CHANNEL_PROVIDER",
                PROVIDER_TYPE,
                "trace-1",
                "request-1",
                "enterprise.acme.internal-im:webhook:verified"
            )
        ));

        assertEquals("extension registration does not expose channel provider: enterprise.acme.internal-im", error.getMessage());
    }

    @Test
    void rejectsDescriptorTypeTraceAndIdempotencyHeaderViolations() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent event = webhookVerifiedEvent(
            "channel-profile-1",
            "enterprise.acme.internal-im:webhook:verified"
        );

        assertEquals(
            "X-AgentYard-Extension-Descriptor-Type must be CHANNEL_PROVIDER",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(event, new NormalizedChannelEventHeaders(
                "acme-channel-provider",
                "TOOL_CONNECTOR",
                PROVIDER_TYPE,
                "trace-1",
                "request-1",
                event.dedupKey()
            ))).getMessage()
        );
        assertEquals(
            "X-AgentYard-Trace-Id is required",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(event, new NormalizedChannelEventHeaders(
                "acme-channel-provider",
                "CHANNEL_PROVIDER",
                PROVIDER_TYPE,
                null,
                "request-1",
                event.dedupKey()
            ))).getMessage()
        );
        assertEquals(
            "Idempotency-Key must equal normalizedEvent.dedupKey",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(event, new NormalizedChannelEventHeaders(
                "acme-channel-provider",
                "CHANNEL_PROVIDER",
                PROVIDER_TYPE,
                "trace-1",
                "request-1",
                "enterprise.acme.internal-im:webhook:other"
            ))).getMessage()
        );
    }

    @Test
    void nonMessageEventDoesNotRequireAssistantBinding() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, null);

        NormalizedChannelInboundEventResult result = service.ingest(
            webhookVerifiedEvent("channel-profile-1", "enterprise.acme.internal-im:webhook:no-binding"),
            validHeaders("enterprise.acme.internal-im:webhook:no-binding")
        );

        assertFalse(result.duplicate());
        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
    }

    private void createProfile(
        String id,
        String providerType,
        ChannelProfileStatus status,
        boolean inboundEnabled,
        ChannelAssistantBinding assistantBinding
    ) {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            id,
            providerType,
            "Acme IM",
            status,
            inboundEnabled,
            Map.of(),
            assistantBinding,
            null,
            false,
            1,
            now,
            now
        ), null);
    }

    private static NormalizedChannelInboundEvent eventWithConversation(
        String channelProfileId,
        NormalizedChannelEventType eventType,
        String dedupKey
    ) {
        return new NormalizedChannelInboundEvent(
            PROVIDER_TYPE,
            channelProfileId,
            eventType,
            dedupKey,
            "evt-1",
            "chat-1",
            "msg-1",
            "user-1",
            null,
            conversation("chat-1"),
            sender("user-1"),
            new NormalizedChannelMessage("msg-1", "TEXT", "hello", List.of(), Map.of()),
            Map.of("text", "hello"),
            Map.of(),
            traceContext(),
            Map.of()
        );
    }

    private static NormalizedChannelInboundEvent webhookVerifiedEvent(String channelProfileId, String dedupKey) {
        return webhookVerifiedEventWithProvider(PROVIDER_TYPE, channelProfileId, dedupKey);
    }

    private static NormalizedChannelInboundEvent webhookVerifiedEventWithProvider(
        String providerType,
        String channelProfileId,
        String dedupKey
    ) {
        return new NormalizedChannelInboundEvent(
            providerType,
            channelProfileId,
            NormalizedChannelEventType.WEBHOOK_VERIFIED,
            dedupKey,
            "evt-verified",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of("verified", true),
            Map.of(),
            traceContext(),
            Map.of()
        );
    }

    private static NormalizedChannelEventHeaders validHeaders(String dedupKey) {
        return validHeaders(PROVIDER_TYPE, dedupKey);
    }

    private static NormalizedChannelEventHeaders validHeaders(String providerType, String dedupKey) {
        return new NormalizedChannelEventHeaders(
            "acme-channel-provider",
            "CHANNEL_PROVIDER",
            providerType,
            "trace-1",
            "request-1",
            dedupKey
        );
    }

    private static NormalizedChannelConversation conversation(String externalConversationId) {
        return new NormalizedChannelConversation(externalConversationId, "GROUP", "Support", Map.of());
    }

    private static NormalizedChannelSender sender(String externalUserId) {
        return new NormalizedChannelSender(externalUserId, "Alice", Map.of());
    }

    private static NormalizedChannelTraceContext traceContext() {
        return new NormalizedChannelTraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null);
    }

    private static ExtensionRegistrationService registrationService() {
        try {
            Path tempFile = Files.createTempFile("agentyard-extension-registration", ".yaml");
            Files.writeString(tempFile, """
                agentyard:
                  extensions:
                    services:
                      - registrationId: acme-channel-provider
                        baseUrl: http://channel.example.com
                        exposes:
                          channelProviderTypes:
                            - enterprise.acme.internal-im
                            - enterprise.acme.ticket
                        auth:
                          type: INTERNAL_TOKEN
                """);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(tempFile.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
