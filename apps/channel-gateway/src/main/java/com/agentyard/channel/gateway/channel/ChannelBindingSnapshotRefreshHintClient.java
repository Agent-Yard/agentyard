package com.agentyard.channel.gateway.channel;

import com.agentyard.contracts.channel.ChannelContracts.ChannelConversationBinding;

public interface ChannelBindingSnapshotRefreshHintClient {
    void bindingSessionAttached(ChannelConversationBinding binding);
}
