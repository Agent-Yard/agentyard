package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void finalDeliveryUsesFrameIdAsFeishuIdempotencyUuid() {
        CapturingCredentialProvider credentialProvider = new CapturingCredentialProvider();
        CapturingMessageSender messageSender = new CapturingMessageSender();
        FeishuGatewayNativeChannelProviderAdapter adapter = new FeishuGatewayNativeChannelProviderAdapter(
            credentialProvider,
            messageSender
        );
        ChannelOutboundFrame frame = finalFrame();

        adapter.consumeOutboundFrame(profile(), frame);

        assertEquals("account-1", credentialProvider.accountId);
        assertEquals("chat_id", messageSender.commands.getFirst().receiveIdType());
        assertEquals("chat-1", messageSender.commands.getFirst().receiveId());
        assertEquals("hello\nworld", messageSender.commands.getFirst().text());
        assertEquals(frame.frameId(), messageSender.commands.getFirst().uuid());
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
        String frameId = "profile-1:session-1:message-1:FINAL_DELIVERY";
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
            null,
            Map.of(
                "sessionMessageId", "message-1",
                "messageSequence", 1,
                "messageBlocks", List.of(
                    Map.of("type", "TEXT", "text", "hello"),
                    Map.of("type", "TEXT", "text", "world")
                )
            ),
            null
        );
    }

    private static final class CapturingCredentialProvider implements FeishuCredentialProvider {
        private String accountId;

        @Override
        public FeishuAppCredential resolve(String accountId, Map<String, Object> profileConfig) {
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
}
