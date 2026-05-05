package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;

public interface ChannelBindingSnapshotRefreshHintClient {
    void bindingSessionAttached(ChannelConversationBinding binding);
}
