package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelEventType;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessage;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelSender;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
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
    void ingestsMessageReceivedAndCreatesConversationBindingFromProfileAssistantSnapshot() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));

        NormalizedChannelInboundEventResult result = service.ingest(messageEvent(
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "enterprise.acme.internal-im:message:msg-1"
        ), validHeaders("enterprise.acme.internal-im:message:msg-1"));

        assertFalse(result.duplicate());
        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
        assertEquals(1, repository.listBindings("channel-profile-1").size());
        var binding = repository.listBindings("channel-profile-1").getFirst();
        assertEquals("chat-1", binding.externalConversationId());
        assertEquals("user-1", binding.externalUserId());
        assertEquals("assistant-1", binding.assistantId());
        assertEquals("user-1", binding.customerId());
    }

    @Test
    void ingestsFileReceivedAndCreatesBinding() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));

        service.ingest(new NormalizedChannelInboundEvent(
            PROVIDER_TYPE,
            "channel-profile-1",
            NormalizedChannelEventType.FILE_RECEIVED,
            "enterprise.acme.internal-im:file:msg-1",
            "evt-1",
            "chat-1",
            "msg-1",
            "user-1",
            null,
            conversation("chat-1"),
            sender("user-1"),
            new NormalizedChannelMessage(
                "msg-1",
                "FILE",
                null,
                List.of(new NormalizedChannelAttachment("att-1", "file-1", "report.pdf", "application/pdf", null, 100L, Map.of())),
                Map.of()
            ),
            Map.of(),
            Map.of(),
            traceContext(),
            Map.of()
        ), validHeaders("enterprise.acme.internal-im:file:msg-1"));

        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
        assertEquals(1, repository.listBindings("channel-profile-1").size());
    }

    @Test
    void duplicateDedupKeyReturnsExistingEventAndDoesNotCreateSecondBinding() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent event = messageEvent(
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "enterprise.acme.internal-im:message:msg-1"
        );
        NormalizedChannelInboundEventResult first = service.ingest(event, validHeaders(event.dedupKey()));
        NormalizedChannelInboundEventResult second = service.ingest(event, validHeaders(event.dedupKey()));

        assertFalse(first.duplicate());
        assertTrue(second.duplicate());
        assertEquals(first.eventId(), second.eventId());
        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
        assertEquals(1, repository.listBindings("channel-profile-1").size());
    }

    @Test
    void duplicateDedupKeyStillValidatesRequestedProfileBeforeReturningExistingEvent() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("inactive-profile", PROVIDER_TYPE, ChannelProfileStatus.INACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("disabled-profile", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, false, new ChannelAssistantBinding("assistant-1", null));
        createProfile("mismatch-profile", TICKET_PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent original = messageEvent(
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "enterprise.acme.internal-im:message:msg-1"
        );
        service.ingest(original, validHeaders(original.dedupKey()));

        assertEquals(
            "channel profile is not ACTIVE: inactive-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("inactive-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "channel profile inbound is disabled: disabled-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("disabled-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "channel profile providerType does not match normalizedEvent.providerType",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("mismatch-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "channel profile not found: missing-profile",
            assertThrows(java.util.NoSuchElementException.class, () -> service.ingest(
                messageEvent("missing-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(1, repository.listInboundEvents("channel-profile-1").size());
    }

    @Test
    void duplicateDedupKeyRejectsDifferentEventSurface() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("channel-profile-2", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        createProfile("ticket-profile", TICKET_PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent original = messageEvent(
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "enterprise.acme.internal-im:message:msg-1"
        );
        service.ingest(original, validHeaders(original.dedupKey()));

        assertEquals(
            "normalized event dedupKey already belongs to a different event surface",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("channel-profile-2", NormalizedChannelEventType.MESSAGE_RECEIVED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "normalized event dedupKey already belongs to a different event surface",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEventWithProvider(TICKET_PROVIDER_TYPE, "ticket-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, original.dedupKey()),
                validHeaders(TICKET_PROVIDER_TYPE, original.dedupKey())
            )).getMessage()
        );
        assertEquals(
            "normalized event dedupKey already belongs to a different event surface",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("channel-profile-1", NormalizedChannelEventType.MESSAGE_UPDATED, original.dedupKey()),
                validHeaders(original.dedupKey())
            )).getMessage()
        );
    }

    @Test
    void eventInsertAndBindingCreationRollbackTogetherWhenBindingFails() {
        String oversizedAssistantId = "assistant-id-that-is-longer-than-the-channel-binding-assistant-id-column-allows";
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding(oversizedAssistantId, null));

        assertThrows(RuntimeException.class, () -> service.ingest(messageEvent(
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "enterprise.acme.internal-im:message:msg-1"
        ), validHeaders("enterprise.acme.internal-im:message:msg-1")));

        assertEquals(0, repository.listInboundEvents("channel-profile-1").size());
        assertEquals(0, repository.listBindings("channel-profile-1").size());
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
                messageEvent("inactive-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, "enterprise.acme.internal-im:message:inactive"),
                validHeaders("enterprise.acme.internal-im:message:inactive")
            )).getMessage()
        );
        assertEquals(
            "channel profile inbound is disabled: disabled-profile",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("disabled-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, "enterprise.acme.internal-im:message:disabled"),
                validHeaders("enterprise.acme.internal-im:message:disabled")
            )).getMessage()
        );
        assertEquals(
            "channel profile providerType does not match normalizedEvent.providerType",
            assertThrows(IllegalArgumentException.class, () -> service.ingest(
                messageEvent("mismatch-profile", NormalizedChannelEventType.MESSAGE_RECEIVED, "enterprise.acme.internal-im:message:mismatch"),
                validHeaders("enterprise.acme.internal-im:message:mismatch")
            )).getMessage()
        );
    }

    @Test
    void rejectsRegistrationThatDoesNotExposeProvider() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.ingest(
            messageEvent("channel-profile-1", NormalizedChannelEventType.MESSAGE_RECEIVED, "enterprise.acme.internal-im:message:msg-1"),
            new NormalizedChannelEventHeaders(
                "missing-channel-provider",
                "CHANNEL_PROVIDER",
                PROVIDER_TYPE,
                "trace-1",
                "request-1",
                "enterprise.acme.internal-im:message:msg-1"
            )
        ));

        assertEquals("extension registration does not expose channel provider: enterprise.acme.internal-im", error.getMessage());
    }

    @Test
    void rejectsDescriptorTypeTraceAndIdempotencyHeaderViolations() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, new ChannelAssistantBinding("assistant-1", null));
        NormalizedChannelInboundEvent event = messageEvent(
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "enterprise.acme.internal-im:message:msg-1"
        );

        assertEquals(
            "X-Lynxus-Extension-Descriptor-Type must be CHANNEL_PROVIDER",
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
            "X-Lynxus-Trace-Id is required",
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
                "enterprise.acme.internal-im:message:other"
            ))).getMessage()
        );
    }

    @Test
    void rejectsMessageEventWhenProfileHasNoAssistantBinding() {
        createProfile("channel-profile-1", PROVIDER_TYPE, ChannelProfileStatus.ACTIVE, true, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.ingest(
            messageEvent("channel-profile-1", NormalizedChannelEventType.MESSAGE_RECEIVED, "enterprise.acme.internal-im:message:msg-1"),
            validHeaders("enterprise.acme.internal-im:message:msg-1")
        ));

        assertEquals("channel profile assistantBinding.assistantId is required for inbound message events", error.getMessage());
        assertEquals(0, repository.listInboundEvents("channel-profile-1").size());
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

    private static NormalizedChannelInboundEvent messageEvent(
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

    private static NormalizedChannelInboundEvent messageEventWithProvider(
        String providerType,
        String channelProfileId,
        NormalizedChannelEventType eventType,
        String dedupKey
    ) {
        return new NormalizedChannelInboundEvent(
            providerType,
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
            Path tempFile = Files.createTempFile("lynxus-extension-registration", ".yaml");
            Files.writeString(tempFile, """
                lynxus:
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
            return new ExtensionRegistrationService(new ExtensionRegistrationProperties(
                tempFile.toString(),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            ));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
