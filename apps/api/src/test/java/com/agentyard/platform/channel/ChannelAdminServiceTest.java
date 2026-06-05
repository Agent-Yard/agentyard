package com.agentyard.platform.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agentyard.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfile;
import com.agentyard.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.agentyard.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.agentyard.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.agentyard.contracts.channel.ChannelContracts.CreateChannelProfileRequest;
import com.agentyard.platform.integration.IntegrationAccountService;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityDecision;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountDto;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountRuntimeSnapshot;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.agentyard.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.agentyard.platform.shared.ApiProblemException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class ChannelAdminServiceTest {
    @Test
    void shouldMaterializeIntegrationAccountSnapshotForCreateAndHideSecretInWebDto() throws Exception {
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        IntegrationAccountService integrationAccountService = mock(IntegrationAccountService.class);
        ChannelAdminService service = new ChannelAdminService(gatewayClient, integrationAccountService);
        Instant now = Instant.parse("2026-04-01T00:00:00Z");

        when(integrationAccountService.requireRuntimeAccountSnapshot(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu"
        )).thenReturn(new IntegrationAccountRuntimeSnapshot(
            "integration-account-1",
            "vault://opaque-ref",
            "Feishu Account",
            IntegrationAccountStatus.ENABLED,
            IntegrationAccountCredentialStatus.ACTIVE,
            true,
            List.of()
        ));
        when(integrationAccountService.getAccount("integration-account-1")).thenReturn(new IntegrationAccountDto(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu",
            "Feishu Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            true,
            true,
            IntegrationAccountCredentialStatus.ACTIVE,
            now,
            now
        ));
        when(integrationAccountService.evaluateAccountAvailability(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu"
        )).thenReturn(new IntegrationAccountAvailabilityDecision(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu",
            IntegrationAccountStatus.ENABLED,
            IntegrationAccountCredentialStatus.ACTIVE,
            null,
            List.of()
        ));
        when(gatewayClient.createProfile(any())).thenReturn(new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            new ChannelAssistantBinding("assistant-1", null),
            "integration-account-1",
            true,
            1,
            now,
            now
        ));

        ChannelProfile profile = service.createProfile(new CreateChannelProfileRequest(
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            new ChannelAssistantBinding("assistant-1", null),
            "integration-account-1"
        ));

        ArgumentCaptor<CreateChannelProfileInternalRequest> requestCaptor = ArgumentCaptor.forClass(CreateChannelProfileInternalRequest.class);
        verify(gatewayClient).createProfile(requestCaptor.capture());
        assertEquals("integration-account-1", requestCaptor.getValue().accountSnapshot().accountId());
        assertEquals("vault://opaque-ref", requestCaptor.getValue().accountSnapshot().externalSecretRef());
        assertEquals("integration-account-1", profile.accountId());
        assertEquals("Feishu Account", profile.integrationAccount().name());

        String publicJson = new ObjectMapper().writeValueAsString(profile);
        assertFalse(publicJson.contains("externalSecretRef"));
        assertFalse(publicJson.contains("vault://opaque-ref"));
    }

    @Test
    void shouldBlockCreateWhenIntegrationAccountAvailabilityFails() {
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        IntegrationAccountService integrationAccountService = mock(IntegrationAccountService.class);
        ChannelAdminService service = new ChannelAdminService(gatewayClient, integrationAccountService);

        when(integrationAccountService.requireRuntimeAccountSnapshot(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu"
        )).thenThrow(new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED",
            "INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED: integration account cannot be used for runtime snapshot"
        ));

        assertThrows(ApiProblemException.class, () -> service.createProfile(new CreateChannelProfileRequest(
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of(),
            null,
            "integration-account-1"
        )));
        verifyNoInteractions(gatewayClient);
    }

    @Test
    void shouldDisableProfileThroughGatewayClientAndHideSecretInWebDto() throws Exception {
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        IntegrationAccountService integrationAccountService = mock(IntegrationAccountService.class);
        ChannelAdminService service = new ChannelAdminService(gatewayClient, integrationAccountService);
        Instant now = Instant.parse("2026-04-01T00:00:00Z");

        when(gatewayClient.deleteProfile("channel-profile-1", 2L)).thenReturn(new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.INACTIVE,
            true,
            Map.of(),
            null,
            "integration-account-1",
            true,
            3,
            now,
            now
        ));
        when(integrationAccountService.getAccount("integration-account-1")).thenReturn(new IntegrationAccountDto(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu",
            "Feishu Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            true,
            true,
            IntegrationAccountCredentialStatus.ACTIVE,
            now,
            now
        ));
        when(integrationAccountService.evaluateAccountAvailability(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu"
        )).thenReturn(new IntegrationAccountAvailabilityDecision(
            "integration-account-1",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu",
            IntegrationAccountStatus.ENABLED,
            IntegrationAccountCredentialStatus.ACTIVE,
            null,
            List.of()
        ));

        ChannelProfile profile = service.deleteProfile("channel-profile-1", 2L);

        verify(gatewayClient).deleteProfile("channel-profile-1", 2L);
        assertEquals(ChannelProfileStatus.INACTIVE, profile.status());
        assertEquals(3, profile.revision());
        String publicJson = new ObjectMapper().writeValueAsString(profile);
        assertFalse(publicJson.contains("externalSecretRef"));
    }

    @Test
    void shouldProxyTemplateBindingWriteAfterMinimalTupleValidation() {
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        IntegrationAccountService integrationAccountService = mock(IntegrationAccountService.class);
        ChannelAdminService service = new ChannelAdminService(gatewayClient, integrationAccountService);
        ChannelTemplateBindingWriteRequest request = new ChannelTemplateBindingWriteRequest(
            "tpl_123",
            null,
            Map.of(),
            "Order status",
            null,
            true,
            null
        );

        service.upsertTemplateBinding("channel-profile-1", "assistant-1", "CARD", "ORDER_STATUS", "v1", request);

        verify(gatewayClient).upsertTemplateBinding("channel-profile-1", "assistant-1", "CARD", "ORDER_STATUS", "v1", request);
    }

    @Test
    void shouldRejectUnknownTemplateBindingMessageTypeBeforeGatewayCall() {
        ChannelGatewayClient gatewayClient = mock(ChannelGatewayClient.class);
        IntegrationAccountService integrationAccountService = mock(IntegrationAccountService.class);
        ChannelAdminService service = new ChannelAdminService(gatewayClient, integrationAccountService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.upsertTemplateBinding(
            "channel-profile-1",
            "assistant-1",
            "UNKNOWN_BLOCK",
            "ORDER_STATUS",
            "v1",
            new ChannelTemplateBindingWriteRequest("tpl_123", null, Map.of(), "Order status", null, true, null)
        ));

        assertEquals("unknown session message block type: UNKNOWN_BLOCK", error.getMessage());
        verifyNoInteractions(gatewayClient);
    }
}
