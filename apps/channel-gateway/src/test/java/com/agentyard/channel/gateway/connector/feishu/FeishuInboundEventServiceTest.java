package com.agentyard.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agentyard.channel.gateway.channel.ChannelAdminRepository;
import com.agentyard.channel.gateway.channel.ChannelInboundSessionDispatcher;
import com.agentyard.channel.gateway.channel.ChannelSessionRuntimeClient;
import com.agentyard.channel.gateway.channel.NormalizedChannelTurnIngestService;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationProperties;
import com.agentyard.channel.gateway.extension.ExtensionRegistrationService;
import com.agentyard.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.agentyard.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;
import com.agentyard.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import com.agentyard.shared.redis.RedisKeyspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import static com.agentyard.channel.gateway.jooq.Tables.CHANNEL_INBOUND_TURN;

class FeishuInboundEventServiceTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;
    private CapturingReactionClient reactionClient;
    private CapturingRuntimeClient runtimeClient;
    private FeishuInboundEventService service;

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
        reactionClient = new CapturingReactionClient();
        runtimeClient = new CapturingRuntimeClient();
        ChannelInboundSessionDispatcher dispatcher = new ChannelInboundSessionDispatcher(repository, runtimeClient);
        service = new FeishuInboundEventService(
            new NormalizedChannelTurnIngestService(repository, registrationService()),
            dispatcher,
            new FeishuTypingReactionService(
                repository,
                new InMemoryTypingReactionStore(),
                accountId -> new FeishuAppCredential(accountId, "app-id", "secret"),
                reactionClient
            )
        );
        createProfile("channel-profile-feishu");
    }

    @Test
    void ingestsLongConnectionTextMessageAndCreatesConversationBinding() {
        service.ingestTextMessage(message("om_123", "hello"));

        var inboundTurn = database.dsl().selectFrom(CHANNEL_INBOUND_TURN).fetchOne();
        assertEquals("feishu", inboundTurn.getProviderType());
        assertEquals("oc_123", inboundTurn.getExternalConversationId());
        assertEquals(1, repository.listBindings("channel-profile-feishu").size());
        var binding = repository.listBindings("channel-profile-feishu").getFirst();
        assertEquals("oc_123", binding.externalConversationId());
        assertEquals("ou_123", binding.externalUserId());
        assertEquals("assistant-1", binding.assistantId());
        assertEquals("user_123", runtimeClient.requests.getFirst().messages().getFirst().sender().senderName());
        assertEquals(List.of("om_123:Typing"), reactionClient.added);
    }

    @Test
    void duplicateLongConnectionTextMessageUsesExistingInboundEvent() {
        service.ingestTextMessage(message("om_123", "hello"));
        service.ingestTextMessage(message("om_123", "hello"));

        assertEquals(1, database.dsl().fetchCount(CHANNEL_INBOUND_TURN));
        assertEquals(1, repository.listBindings("channel-profile-feishu").size());
        assertEquals(List.of("om_123:Typing"), reactionClient.added);
    }

    private void createProfile(String profileId) {
        Instant now = Instant.parse("2026-04-30T00:00:00Z");
        repository.createProfile(new ChannelGatewayProfile(
            profileId,
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "Feishu",
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

    private static FeishuInboundTextMessage message(String messageId, String text) {
        return new FeishuInboundTextMessage(
            "channel-profile-feishu",
            "cli_test",
            "req_123",
            "evt_123",
            "tenant_123",
            messageId,
            "oc_123",
            "p2p",
            "ou_123",
            "user_123",
            "union_123",
            text,
            "1710000000000",
            Map.of("message", Map.of("messageId", messageId))
        );
    }

    private static final class CapturingReactionClient implements FeishuMessageReactionClient {
        private final List<String> added = new ArrayList<>();

        @Override
        public FeishuAddReactionResult addReaction(FeishuAddReactionCommand command) {
            added.add(command.messageId() + ":" + command.emojiType());
            return new FeishuAddReactionResult("reaction-1");
        }

        @Override
        public void deleteReaction(FeishuDeleteReactionCommand command) {
        }
    }

    private static final class InMemoryTypingReactionStore extends FeishuTypingReactionStore {
        private final Map<String, FeishuTypingReactionState> byDedupKey = new LinkedHashMap<>();

        private InMemoryTypingReactionStore() {
            super(
                mock(StringRedisTemplate.class),
                new RedisKeyspace("test"),
                new ObjectMapper(),
                new FeishuTypingReactionProperties()
            );
        }

        @Override
        boolean saveIfAbsent(FeishuTypingReactionState state) {
            if (byDedupKey.containsKey(state.dedupKey())) {
                return false;
            }
            byDedupKey.put(state.dedupKey(), state);
            return true;
        }

        @Override
        boolean attachSessionByDedupKey(String channelProfileId, String dedupKey, String sessionId) {
            return byDedupKey.containsKey(dedupKey);
        }

        @Override
        Optional<FeishuTypingReactionState> claimByDedupKey(String channelProfileId, String dedupKey) {
            return Optional.ofNullable(byDedupKey.remove(dedupKey));
        }
    }

    private static final class CapturingRuntimeClient implements ChannelSessionRuntimeClient {
        private final List<ChannelInboundSessionTurnRequest> requests = new ArrayList<>();

        @Override
        public ChannelInboundSessionTurnResponse dispatchInboundTurn(ChannelInboundSessionTurnRequest request) {
            requests.add(request);
            return new ChannelInboundSessionTurnResponse(
                "session-feishu",
                "turn-session-feishu",
                SessionMessageDeliveryStatus.ACCEPTED,
                List.of("session-message-feishu"),
                List.of(new AcceptedSessionMessageAllocation(0, null, "session-message-feishu", 0)),
                List.of(),
                null
            );
        }
    }

    private static ExtensionRegistrationService registrationService() {
        return new ExtensionRegistrationService(
            new ExtensionRegistrationProperties(null),
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        );
    }
}
