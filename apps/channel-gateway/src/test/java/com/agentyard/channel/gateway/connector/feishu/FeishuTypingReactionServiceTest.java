package com.agentyard.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.agentyard.channel.gateway.channel.ChannelAdminRepository;
import com.agentyard.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageRole;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelSenderType;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTurnMessage;
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

class FeishuTypingReactionServiceTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminRepository repository;
    private InMemoryTypingReactionStore reactionStore;
    private CapturingReactionClient reactionClient;
    private FeishuTypingReactionService service;

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
        reactionStore = new InMemoryTypingReactionStore();
        reactionClient = new CapturingReactionClient();
        service = new FeishuTypingReactionService(
            repository,
            reactionStore,
            accountId -> new FeishuAppCredential(accountId, "app-id", "secret"),
            reactionClient
        );
        createProfile();
    }

    @Test
    void inboundMessageCreatesTypingReactionAndFirstOutboundFrameDeletesIt() {
        saveBinding("session-1");
        NormalizedChannelInboundTurn turn = inboundTurn("dedup-1", "om_1");

        service.beginInboundTypingReaction(turn);
        service.deleteTypingReactionOnFirstOutboundFrame(profile(), "session-1", "oc_1");
        service.deleteTypingReactionOnFirstOutboundFrame(profile(), "session-1", "oc_1");

        assertEquals(List.of("om_1:Typing"), reactionClient.added);
        assertEquals(List.of("om_1:reaction-1"), reactionClient.deleted);
    }

    @Test
    void firstOutboundFrameFallsBackToConversationBeforeSessionIsAttached() {
        saveBinding(null);
        NormalizedChannelInboundTurn turn = inboundTurn("dedup-3", "om_3");

        service.beginInboundTypingReaction(turn);
        service.deleteTypingReactionOnFirstOutboundFrame(profile(), "session-1", "oc_1");

        assertEquals(List.of("om_3:Typing"), reactionClient.added);
        assertEquals(List.of("om_3:reaction-1"), reactionClient.deleted);
    }

    @Test
    void dispatchFailureDeletesReactionBeforeSessionIsAttached() {
        NormalizedChannelInboundTurn turn = inboundTurn("dedup-2", "om_2");
        service.beginInboundTypingReaction(turn);

        service.afterDispatchFailed(turn, null, new IllegalStateException("busy"));

        assertEquals(List.of("om_2:Typing"), reactionClient.added);
        assertEquals(List.of("om_2:reaction-1"), reactionClient.deleted);
    }

    private void createProfile() {
        repository.createProfile(profile(), null);
    }

    private static ChannelGatewayProfile profile() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelGatewayProfile(
            "channel-profile-feishu",
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
        );
    }

    private void saveBinding(String sessionId) {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        repository.saveBindingForConversation(new ChannelConversationBinding(
            "binding-1",
            "channel-profile-feishu",
            "oc_1",
            "ou_1",
            "assistant-1",
            "ou_1",
            sessionId,
            ChannelConversationBindingStatus.ACTIVE,
            Map.of(),
            now,
            now
        ));
    }

    private static NormalizedChannelInboundTurn inboundTurn(String dedupKey, String messageId) {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        NormalizedChannelMessageSender sender = new NormalizedChannelMessageSender(
            NormalizedChannelSenderType.CUSTOMER,
            "ou_1",
            null,
            Map.of()
        );
        return new NormalizedChannelInboundTurn(
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "channel-profile-feishu",
            dedupKey,
            "oc_1",
            "ou_1",
            new NormalizedChannelConversation("oc_1", "p2p", null, Map.of()),
            sender,
            List.of(new NormalizedChannelTurnMessage(
                messageId,
                messageId,
                now,
                NormalizedChannelMessageRole.USER,
                sender,
                "TEXT",
                "hello",
                List.of(),
                Map.of()
            )),
            Map.of(),
            Map.of(),
            new NormalizedChannelTraceContext("00-00000000000000000000000000000000-0000000000000000-01", null),
            Map.of()
        );
    }

    private static final class CapturingReactionClient implements FeishuMessageReactionClient {
        private final List<String> added = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public FeishuAddReactionResult addReaction(FeishuAddReactionCommand command) {
            added.add(command.messageId() + ":" + command.emojiType());
            return new FeishuAddReactionResult("reaction-1");
        }

        @Override
        public void deleteReaction(FeishuDeleteReactionCommand command) {
            deleted.add(command.messageId() + ":" + command.reactionId());
        }
    }

    private static final class InMemoryTypingReactionStore extends FeishuTypingReactionStore {
        private final Map<String, FeishuTypingReactionState> byDedupKey = new LinkedHashMap<>();
        private final Map<String, String> dedupKeyBySession = new LinkedHashMap<>();
        private final Map<String, String> dedupKeyByConversation = new LinkedHashMap<>();

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
            dedupKeyByConversation.put(state.channelProfileId() + ":" + state.externalConversationId(), state.dedupKey());
            if (state.sessionId() != null) {
                dedupKeyBySession.put(state.channelProfileId() + ":" + state.sessionId(), state.dedupKey());
            }
            return true;
        }

        @Override
        boolean attachSessionByDedupKey(String channelProfileId, String dedupKey, String sessionId) {
            FeishuTypingReactionState state = byDedupKey.get(dedupKey);
            if (state == null) {
                return false;
            }
            byDedupKey.put(dedupKey, state.withSessionId(sessionId));
            dedupKeyBySession.put(channelProfileId + ":" + sessionId, dedupKey);
            return true;
        }

        @Override
        Optional<FeishuTypingReactionState> claimByDedupKey(String channelProfileId, String dedupKey) {
            return Optional.ofNullable(byDedupKey.remove(dedupKey));
        }

        @Override
        Optional<FeishuTypingReactionState> claimBySession(String channelProfileId, String sessionId) {
            String dedupKey = dedupKeyBySession.remove(channelProfileId + ":" + sessionId);
            return dedupKey == null ? Optional.empty() : claimByDedupKey(channelProfileId, dedupKey);
        }

        @Override
        Optional<FeishuTypingReactionState> claimByConversation(String channelProfileId, String externalConversationId) {
            String dedupKey = dedupKeyByConversation.remove(channelProfileId + ":" + externalConversationId);
            return dedupKey == null ? Optional.empty() : claimByDedupKey(channelProfileId, dedupKey);
        }

        @Override
        void restore(FeishuTypingReactionState state) {
            saveIfAbsent(state);
        }
    }
}
