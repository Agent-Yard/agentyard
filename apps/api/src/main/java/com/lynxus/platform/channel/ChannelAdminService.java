package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileRequest;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChannelAdminService {
    private final ChannelGatewayClient channelGatewayClient;

    public ChannelAdminService(ChannelGatewayClient channelGatewayClient) {
        this.channelGatewayClient = channelGatewayClient;
    }

    public List<ChannelProfile> listProfiles() {
        return channelGatewayClient.listProfiles();
    }

    public ChannelProfile createProfile(CreateChannelProfileRequest request) {
        return channelGatewayClient.createProfile(request);
    }

    public ChannelProfile getProfile(String channelProfileId) {
        return channelGatewayClient.getProfile(channelProfileId);
    }

    public ChannelProfile updateProfile(String channelProfileId, UpdateChannelProfileRequest request) {
        return channelGatewayClient.updateProfile(channelProfileId, request);
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return channelGatewayClient.listBindings(channelProfileId);
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        return channelGatewayClient.listInboundEvents(channelProfileId);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String channelProfileId) {
        return channelGatewayClient.listOutboundDeliveries(channelProfileId);
    }
}
