package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelEventType;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessage;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelSender;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionMessageResponse;
import com.lynxus.extension.sdk.protocol.DescriptorType;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelInboundSessionDispatcherTest {
    private static final String PROVIDER_TYPE = "feishu";
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;
    private NormalizedChannelEventIngestService ingestService;
    private CapturingSessionRuntimeClient runtimeClient;

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
        ingestService = new NormalizedChannelEventIngestService(repository, registrationService());
        runtimeClient = new CapturingSessionRuntimeClient();
        createProfile();
    }

    @Test
    void dispatchesInboundMessageToSessionRuntimeAndStoresReturnedSessionId() {
        NormalizedChannelInboundEvent event = messageEvent();
        var ingestResult = ingestService.ingest(event, headers(event.dedupKey()));
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient, Runnable::run);

        var dispatchResult = dispatcher.dispatch(event, ingestResult);

        assertEquals("session-v2-abc12345", dispatchResult.sessionId());
        assertEquals("assistant-1", runtimeClient.request.assistantId());
        assertEquals("user-1", runtimeClient.request.customerId());
        assertEquals("chat-1", runtimeClient.request.externalConversationId());
        assertEquals("hello", ((Map<?, ?>) runtimeClient.request.message().blocks().getFirst()).get("text"));
        var binding = repository.listBindings("channel-profile-1").getFirst();
        assertEquals("session-v2-abc12345", binding.sessionId());
    }

    @Test
    void sendsBindingSnapshotRefreshHintAfterAttachSession() {
        NormalizedChannelInboundEvent event = messageEvent();
        var ingestResult = ingestService.ingest(event, headers(event.dedupKey()));
        List<String> order = new ArrayList<>();
        CapturingHintClient hintClient = new CapturingHintClient(order);
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(
            repository,
            runtimeClient,
            hintClient,
            Runnable::run
        );

        dispatcher.dispatch(event, ingestResult);

        assertEquals("session-v2-abc12345", hintClient.binding.sessionId());
        assertEquals(List.of("hint"), order);
    }

    @Test
    void dispatchAsyncQueuesRuntimeWorkWithoutBlockingProviderAckPath() {
        NormalizedChannelInboundEvent event = messageEvent();
        var ingestResult = ingestService.ingest(event, headers(event.dedupKey()));
        CapturingExecutor executor = new CapturingExecutor();
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient, executor);

        dispatcher.dispatchAsync(event, ingestResult);

        assertNull(runtimeClient.request);
        assertEquals(1, executor.tasks.size());

        executor.tasks.remove().run();

        assertEquals("chat-1", runtimeClient.request.externalConversationId());
        assertEquals("session-v2-abc12345", repository.listBindings("channel-profile-1").getFirst().sessionId());
    }

    @Test
    void dispatchAsyncDoesNotPropagateExecutorRejectionToProviderAckPath() {
        NormalizedChannelInboundEvent event = messageEvent();
        var ingestResult = ingestService.ingest(event, headers(event.dedupKey()));
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient, rejectingExecutor);

        dispatcher.dispatchAsync(event, ingestResult);

        assertNull(runtimeClient.request);
    }

    private void createProfile() {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            "channel-profile-1",
            PROVIDER_TYPE,
            "Internal IM",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            new ChannelAssistantBinding("assistant-1", null),
            "integration-account-1",
            false,
            1,
            now,
            now
        ), null);
    }

    private static NormalizedChannelInboundEvent messageEvent() {
        return new NormalizedChannelInboundEvent(
            PROVIDER_TYPE,
            "channel-profile-1",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            "feishu:message:msg-1",
            "evt-1",
            "chat-1",
            "msg-1",
            "user-1",
            Instant.parse("2026-04-30T00:00:01Z"),
            new NormalizedChannelConversation("chat-1", "P2P", null, Map.of()),
            new NormalizedChannelSender("user-1", "Alice", Map.of()),
            new NormalizedChannelMessage("msg-1", "TEXT", "hello", List.of(), Map.of()),
            Map.of("externalConversationId", "chat-1"),
            Map.of("raw", "payload"),
            new NormalizedChannelTraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", null),
            Map.of("source", "test")
        );
    }

    private static NormalizedChannelEventHeaders headers(String dedupKey) {
        return new NormalizedChannelEventHeaders(
            ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID,
            DescriptorType.CHANNEL_PROVIDER.wireValue(),
            PROVIDER_TYPE,
            "trace-1",
            "request-1",
            dedupKey
        );
    }

    private static ExtensionRegistrationService registrationService() {
        return new ExtensionRegistrationService(
            new ExtensionRegistrationProperties(null),
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        );
    }

    private static final class CapturingSessionRuntimeClient implements ChannelSessionRuntimeClient {
        private ChannelInboundSessionMessageRequest request;

        @Override
        public ChannelInboundSessionMessageResponse dispatchInboundMessage(ChannelInboundSessionMessageRequest request) {
            this.request = request;
            return new ChannelInboundSessionMessageResponse("session-v2-abc12345", "IDLE");
        }
    }

    private static final class CapturingHintClient implements ChannelBindingSnapshotRefreshHintClient {
        private final List<String> order;
        private com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding binding;

        private CapturingHintClient(List<String> order) {
            this.order = order;
        }

        @Override
        public void bindingSessionAttached(com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding binding) {
            this.binding = binding;
            order.add("hint");
        }
    }

    private static final class CapturingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }
}
