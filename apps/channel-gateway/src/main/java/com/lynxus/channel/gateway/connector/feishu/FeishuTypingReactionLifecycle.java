package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;

interface FeishuTypingReactionLifecycle {
    FeishuTypingReactionLifecycle NOOP = (profile, sessionId, externalConversationId) -> {
    };

    void deleteTypingReactionOnFirstOutboundFrame(
        ChannelGatewayProfile profile,
        String sessionId,
        String externalConversationId
    );
}
