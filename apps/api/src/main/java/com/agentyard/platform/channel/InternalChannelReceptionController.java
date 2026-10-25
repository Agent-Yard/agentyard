package com.agentyard.platform.channel;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.contracts.channel.ChannelContracts.*;
import com.agentyard.platform.agent.AgentService;
import com.agentyard.platform.integration.InternalRuntimeAuth;
import com.agentyard.platform.shared.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class InternalChannelReceptionController {
    private final AgentService agents;
    private final ChannelGatewayClient gateway;
    private final InternalRuntimeAuth auth;
    private final ChannelParticipantIdentityRepository identities;
    public InternalChannelReceptionController(AgentService agents, ChannelGatewayClient gateway, InternalRuntimeAuth auth,
        ChannelParticipantIdentityRepository identities) {
        this.agents = agents; this.gateway = gateway; this.auth = auth; this.identities=identities;
    }

    @PostMapping("/api/internal/channel-reception/messages")
    public ApiResponse<ChannelReceptionResponse> receive(@RequestHeader(value="Authorization", required=false) String authorization,
        @RequestBody ChannelReceptionRequest request) {
        auth.requireBearer(authorization);
        var profile = gateway.getProfile(required(request.channelProfileId(), "channelProfileId"));
        if (profile.status() != ChannelProfileStatus.ACTIVE || !profile.inboundEnabled() || profile.entryBinding() == null
            || !profile.entryBinding().entryId().equals(request.entryId())) {
            throw new IllegalArgumentException("channel entry is unavailable or does not match the configured reception entry");
        }
        String conversation = required(request.externalConversationId(), "externalConversationId");
        if (request.messages().isEmpty()) throw new IllegalArgumentException("messages are required");
        List<ChannelReceptionAllocation> allocations = new ArrayList<>();
        for (int index=0; index<request.messages().size(); index++) {
            var incoming = request.messages().get(index);
            var actor = agents.ensureHumanIdentity("channel:" + profile.id() + ":" + conversation,
                required(incoming.externalUserId(), "externalUserId"), required(incoming.displayName(), "displayName"));
            identities.record(actor.id(),profile.id(),conversation,incoming.externalUserId());
            var reception = agents.receptionFor(actor.id(), request.entryId());
            Map<String,Object> body = new LinkedHashMap<>();
            body.put("blocks", incoming.message().blocks());
            body.put("metadata", incoming.message().metadata());
            body.put("channel", Map.of("profileId",profile.id(),"conversationId",conversation,
                "externalMessageId", required(incoming.externalMessageId(), "externalMessageId")));
            var message = agents.sendAs(actor.id(), new SendMessage(
                "channel:" + profile.id() + ":" + conversation + ":" + incoming.externalMessageId(),
                reception.session().participantId(), null, null, MessageKind.MESSAGE, body));
            allocations.add(new ChannelReceptionAllocation(index, message.id(), reception.session().id(), actor.id()));
        }
        return ApiResponse.ok(new ChannelReceptionResponse(allocations.getFirst().sessionId(), allocations, List.of(), null));
    }

    private String required(String value,String field) {
        if(value==null || value.isBlank()) throw new IllegalArgumentException(field+" is required");
        return value;
    }
}
