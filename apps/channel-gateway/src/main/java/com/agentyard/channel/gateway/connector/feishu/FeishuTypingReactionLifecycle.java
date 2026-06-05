package com.agentyard.channel.gateway.connector.feishu;

import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;

interface FeishuTypingReactionLifecycle {
    FeishuTypingReactionLifecycle NOOP = (profile, sessionId, externalConversationId) -> {
    };

    void deleteTypingReactionOnFirstOutboundFrame(
        ChannelGatewayProfile profile,
        String sessionId,
        String externalConversationId
    );
}
