package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.contracts.channel.ChannelContracts;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrame;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FeishuGatewayNativeChannelProviderAdapterTest {
    @Test
    void finalDeliveryUsesBoundedFeishuIdempotencyUuid() {
        CapturingCredentialProvider credentialProvider = new CapturingCredentialProvider();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            credentialProvider,
            messageSender,
            FeishuTypingReactionLifecycle.NOOP
        );
        ChannelOutboundFrame frame = finalFrame();

        adapter.consumeOutboundFrame(profile(), frame);

        assertEquals("account-1", credentialProvider.accountId);
        assertEquals("chat_id", messageSender.commands.getFirst().receiveIdType());
        assertEquals("chat-1", messageSender.commands.getFirst().receiveId());
        assertEquals("hello\nworld", messageSender.commands.getFirst().text());
        assertEquals("cof-b756d04622f455de1bce7530f2c6eadf", messageSender.commands.getFirst().uuid());
        assertTrue(messageSender.commands.getFirst().uuid().length() <= 50);
    }

    @Test
    void finalDeliverySkipsUnsupportedBlocksWithoutFailingCheckpointPath() {
        CapturingCredentialProvider credentialProvider = new CapturingCredentialProvider();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            credentialProvider,
            messageSender,
            FeishuTypingReactionLifecycle.NOOP
        );
        ChannelOutboundFrame frame = finalFrameWithBlocks(List.of(
            Map.of("type", "IMAGE", "url", "https://example.invalid/image.png"),
            Map.of("type", "FILE", "fileId", "file-1")
        ));

        adapter.consumeOutboundFrame(profile(), frame);

        assertNull(credentialProvider.accountId);
        assertEquals(0, messageSender.commands.size());
    }

    @Test
    void typingStartOnlyClearsTypingReactionWithoutSendingMessage() {
        CapturingTypingReactionLifecycle typingLifecycle = new CapturingTypingReactionLifecycle();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            messageSender,
            typingLifecycle
        );
        ChannelOutboundFrame frame = typingFrame();

        adapter.consumeOutboundFrame(profile(), frame);

        assertEquals(List.of("session-1"), typingLifecycle.deletedSessions);
        assertEquals(0, messageSender.commands.size());
    }

    @Test
    void descriptorDeclaresTypingSupportForReactionLifecycleFrames() {
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            new CapturingMessageSender(),
            FeishuTypingReactionLifecycle.NOOP
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> outbound = (Map<String, Object>) adapter.descriptor().get("outbound");

        assertEquals(true, outbound.get("supportsTyping"));
    }

    @Test
    void descriptorKeepsAppIdOnAccountConfigAndSecretsOnCredential() {
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            new CapturingCredentialProvider(),
            new CapturingMessageSender(),
            FeishuTypingReactionLifecycle.NOOP
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> accountConfigSchema = (Map<String, Object>) adapter.descriptor().get("accountConfigSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> accountProperties = (Map<String, Object>) accountConfigSchema.get("properties");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> accountConfigUiSchema =
            (List<Map<String, Object>>) adapter.descriptor().get("accountConfigUiSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> credentialSchema = (Map<String, Object>) adapter.descriptor().get("credentialSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> credentialProperties = (Map<String, Object>) credentialSchema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> credentialRequired = (List<String>) credentialSchema.get("required");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> credentialUiSchema =
            (List<Map<String, Object>>) adapter.descriptor().get("credentialUiSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileConfigSchema = (Map<String, Object>) adapter.descriptor().get("configSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> profileProperties = (Map<String, Object>) profileConfigSchema.get("properties");

        assertTrue(accountProperties.containsKey("appId"));
        assertEquals(List.of("appId"), accountConfigSchema.get("required"));
        assertEquals(List.of("/appId"), accountConfigUiSchema.stream()
            .map(field -> field.get("key"))
            .toList());
        assertEquals(true, accountConfigUiSchema.getFirst().get("required"));
        assertTrue(credentialProperties.containsKey("appSecret"));
        assertTrue(credentialProperties.containsKey("verificationToken"));
        assertTrue(credentialProperties.containsKey("encryptKey"));
        assertEquals(List.of("appSecret"), credentialRequired);
        assertEquals(List.of("/appSecret", "/verificationToken", "/encryptKey"), credentialUiSchema.stream()
            .map(field -> field.get("key"))
            .toList());
        assertEquals(true, credentialUiSchema.get(0).get("required"));
        assertEquals(false, credentialUiSchema.get(1).get("required"));
        assertEquals(false, credentialUiSchema.get(2).get("required"));
        assertTrue(profileProperties.containsKey("receiveIdType"));
        assertEquals(false, profileProperties.containsKey("appId"));
        assertEquals(false, profileProperties.containsKey("verificationToken"));
        assertEquals(false, profileProperties.containsKey("encryptKey"));
    }

    private static ChannelGatewayProfile profile() {
        Instant now = Instant.parse("2026-05-05T00:00:00Z");
        return new ChannelGatewayProfile(
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "Feishu",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("receiveIdType", "chat_id"),
            null,
            "account-1",
            false,
            1,
            now,
            now
        );
    }

    private static ChannelOutboundFrame finalFrame() {
        return finalFrameWithBlocks(List.of(
            Map.of("type", "TEXT", "text", "hello"),
            Map.of("type", "TEXT", "text", "world")
        ));
    }

    private static ChannelOutboundFrame finalFrameWithBlocks(List<Map<String, Object>> messageBlocks) {
        String frameId = "channel-profile-d28237bb:session-v2-b50aa165:session-message-4b8e6432-b10a-358c-a77e-197599f37579:FINAL_DELIVERY";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            null,
            null,
            null,
            1L,
            ChannelOutboundFrameKind.FINAL_DELIVERY,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of(
                "sessionMessageId", "message-1",
                "messageSequence", 1,
                "messageBlocks", messageBlocks
            ),
            null
        );
    }

    private static ChannelOutboundFrame typingFrame() {
        String frameId = "profile-1:turn-1:1:TYPING_START";
        return new ChannelOutboundFrame(
            ChannelContracts.CHANNEL_OUTBOUND_FRAME_PROTOCOL,
            frameId,
            "profile-1",
            FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE,
            "assistant-1",
            "chat-1",
            "session-1",
            "turn-1",
            "turn-1:exec-1",
            1L,
            null,
            ChannelOutboundFrameKind.TYPING_START,
            Instant.parse("2026-05-05T00:00:00Z"),
            frameId,
            Map.of("messageId", "message-1"),
            null
        );
    }

    private static final class CapturingCredentialProvider implements FeishuCredentialProvider {
        private String accountId;

        @Override
        public FeishuAppCredential resolve(String accountId) {
            this.accountId = accountId;
            return new FeishuAppCredential(accountId, "app-id", "secret");
        }
    }

    private static final class CapturingMessageSender implements FeishuMessageSender {
        private final List<FeishuSendTextCommand> commands = new ArrayList<>();

        @Override
        public FeishuSendTextResult sendText(FeishuSendTextCommand command) {
            commands.add(command);
            return new FeishuSendTextResult("external-message-1", Map.of());
        }
    }

    private static final class CapturingTypingReactionLifecycle implements FeishuTypingReactionLifecycle {
        private final List<String> deletedSessions = new ArrayList<>();

        @Override
        public void deleteTypingReactionOnFirstOutboundFrame(
            ChannelGatewayProfile profile,
            String sessionId,
            String externalConversationId
        ) {
            deletedSessions.add(sessionId);
        }
    }
}
