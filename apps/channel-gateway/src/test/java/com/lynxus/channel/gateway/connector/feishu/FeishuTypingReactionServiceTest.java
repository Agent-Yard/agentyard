package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelConversation;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelEventType;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessage;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelSender;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import com.lynxus.shared.redis.RedisKeyspace;
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
        NormalizedChannelInboundEvent event = inboundEvent("dedup-1", "om_1");

        service.beginInboundTypingReaction(event);
        service.deleteTypingReactionOnFirstOutboundFrame(profile(), "session-1", "oc_1");
        service.deleteTypingReactionOnFirstOutboundFrame(profile(), "session-1", "oc_1");

        assertEquals(List.of("om_1:Typing"), reactionClient.added);
        assertEquals(List.of("om_1:reaction-1"), reactionClient.deleted);
    }

    @Test
    void firstOutboundFrameFallsBackToConversationBeforeSessionIsAttached() {
        saveBinding(null);
        NormalizedChannelInboundEvent event = inboundEvent("dedup-3", "om_3");

        service.beginInboundTypingReaction(event);
        service.deleteTypingReactionOnFirstOutboundFrame(profile(), "session-1", "oc_1");

        assertEquals(List.of("om_3:Typing"), reactionClient.added);
        assertEquals(List.of("om_3:reaction-1"), reactionClient.deleted);
    }

    @Test
    void dispatchFailureDeletesReactionBeforeSessionIsAttached() {
        NormalizedChannelInboundEvent event = inboundEvent("dedup-2", "om_2");
        service.beginInboundTypingReaction(event);

        service.afterDispatchFailed(event, new NormalizedChannelInboundEventResult("event-1", false), new IllegalStateException("busy"));

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

    private static NormalizedChannelInboundEvent inboundEvent(String dedupKey, String messageId) {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new NormalizedChannelInboundEvent(
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "channel-profile-feishu",
            NormalizedChannelEventType.MESSAGE_RECEIVED,
            dedupKey,
            messageId,
            "oc_1",
            messageId,
            "ou_1",
            now,
            new NormalizedChannelConversation("oc_1", "p2p", null, Map.of()),
            new NormalizedChannelSender("ou_1", null, Map.of()),
            new NormalizedChannelMessage(messageId, "TEXT", "hello", List.of(), Map.of()),
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
