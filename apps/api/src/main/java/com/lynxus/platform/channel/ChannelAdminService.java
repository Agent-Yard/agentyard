package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelAccount;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelAccountRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelAccountRequest;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChannelAdminService {
    private final ChannelGatewayClient channelGatewayClient;

    public ChannelAdminService(ChannelGatewayClient channelGatewayClient) {
        this.channelGatewayClient = channelGatewayClient;
    }

    public List<ChannelAccount> listAccounts() {
        return channelGatewayClient.listAccounts();
    }

    public ChannelAccount createAccount(CreateChannelAccountRequest request) {
        return channelGatewayClient.createAccount(request);
    }

    public ChannelAccount getAccount(String accountId) {
        return channelGatewayClient.getAccount(accountId);
    }

    public ChannelAccount updateAccount(String accountId, UpdateChannelAccountRequest request) {
        return channelGatewayClient.updateAccount(accountId, request);
    }

    public List<ChannelConversationBinding> listBindings(String accountId) {
        return channelGatewayClient.listBindings(accountId);
    }

    public List<ChannelInboundEvent> listInboundEvents(String accountId) {
        return channelGatewayClient.listInboundEvents(accountId);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String accountId) {
        return channelGatewayClient.listOutboundDeliveries(accountId);
    }
}
