package com.lynxus.channel.gateway.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalChannelAdminControllerTest {
    @Test
    void shouldReturnConflictForStaleRevision() throws Exception {
        ChannelAdminService service = mock(ChannelAdminService.class);
        when(service.updateProfile(eq("channel-profile-1"), any(UpdateChannelProfileInternalRequest.class)))
            .thenThrow(new ConflictException("channel profile revision conflict: channel-profile-1"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalChannelAdminController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(put("/internal/channel-admin/profiles/channel-profile-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "providerType": "feishu",
                      "displayName": "飞书客服机器人",
                      "config": {},
                      "expectedRevision": 1
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("channel profile revision conflict: channel-profile-1"));
    }

    @Test
    void shouldExposeBindingSnapshotBySessionAndReturnConflictForDuplicateActiveBindings() throws Exception {
        ChannelAdminService service = mock(ChannelAdminService.class);
        when(service.getActiveBindingSnapshotBySession("session-1")).thenReturn(new ChannelOutboundBindingSnapshot(
            "channel-binding-1",
            "session-1",
            "channel-profile-1",
            "feishu",
            "chat-1",
            "user-1",
            "assistant-1",
            "customer-1",
            "ACTIVE",
            ChannelProfileStatus.ACTIVE,
            2,
            Instant.parse("2026-05-04T00:00:00Z"),
            Instant.parse("2026-05-04T00:00:01Z"),
            Instant.parse("2026-05-04T00:00:01Z")
        ));
        when(service.getActiveBindingSnapshotBySession("session-duplicate"))
            .thenThrow(new ConflictException("duplicate ACTIVE channel conversation bindings for session: session-duplicate"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalChannelBindingSnapshotController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/internal/channel-admin/bindings/by-session/session-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bindingId").value("channel-binding-1"))
            .andExpect(jsonPath("$.data.profileRevision").value(2));

        mockMvc.perform(get("/internal/channel-admin/bindings/by-session/session-duplicate"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("duplicate ACTIVE channel conversation bindings for session: session-duplicate"));
    }

    @Test
    void shouldDisableProfileWithExpectedRevisionQueryParam() throws Exception {
        ChannelAdminService service = mock(ChannelAdminService.class);
        when(service.deleteProfile("channel-profile-1", 3L)).thenReturn(new ChannelGatewayProfile(
            "channel-profile-1",
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.INACTIVE,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            "integration-account-1",
            true,
            4,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:01:00Z")
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalChannelAdminController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(delete("/internal/channel-admin/profiles/channel-profile-1")
                .queryParam("expectedRevision", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("INACTIVE"))
            .andExpect(jsonPath("$.data.revision").value(4))
            .andExpect(jsonPath("$.data.hasExternalSecretRef").value(true));
    }

    @Test
    void shouldReturnConflictForStaleDeleteRevision() throws Exception {
        ChannelAdminService service = mock(ChannelAdminService.class);
        when(service.deleteProfile("channel-profile-1", 2L))
            .thenThrow(new ConflictException("channel profile revision conflict: channel-profile-1"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalChannelAdminController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(delete("/internal/channel-admin/profiles/channel-profile-1")
                .queryParam("expectedRevision", "2"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("channel profile revision conflict: channel-profile-1"));
    }

    @Test
    void shouldExposeTemplateBindingCrudRoutes() throws Exception {
        ChannelAdminService service = mock(ChannelAdminService.class);
        ChannelTemplateBinding binding = new ChannelTemplateBinding(
            "channel-template-binding-1",
            "channel-profile-1",
            "assistant-1",
            "CARD",
            "ORDER_STATUS",
            "v1",
            "tpl_123",
            "published",
            true,
            Map.of("type", "object"),
            "Order status",
            "https://provider.example.com/templates/tpl_123",
            1,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-04-01T00:00:00Z")
        );
        when(service.listTemplateBindings("channel-profile-1")).thenReturn(List.of(binding));
        when(service.upsertTemplateBinding(
            eq("channel-profile-1"),
            eq("assistant-1"),
            eq("CARD"),
            eq("ORDER_STATUS"),
            eq("v1"),
            any(ChannelTemplateBindingWriteRequest.class)
        )).thenReturn(binding);
        when(service.deleteTemplateBinding("channel-profile-1", "assistant-1", "CARD", "ORDER_STATUS", "v1", 1L))
            .thenReturn(new ChannelTemplateBinding(
                binding.id(),
                binding.channelProfileId(),
                binding.assistantId(),
                binding.messageType(),
                binding.messageSubtype(),
                binding.messageVersion(),
                binding.externalTemplateId(),
                binding.externalTemplateVersion(),
                false,
                binding.variableSchema(),
                binding.displayName(),
                binding.externalEditUrl(),
                2,
                binding.createdAt(),
                Instant.parse("2026-04-01T00:01:00Z")
            ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalChannelAdminController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/internal/channel-admin/profiles/channel-profile-1/template-bindings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].externalTemplateId").value("tpl_123"));

        mockMvc.perform(put("/internal/channel-admin/profiles/channel-profile-1/template-bindings/assistant-1/CARD/ORDER_STATUS/v1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "externalTemplateId": "tpl_123",
                      "variableSchema": {},
                      "displayName": "Order status",
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.revision").value(1));

        mockMvc.perform(delete("/internal/channel-admin/profiles/channel-profile-1/template-bindings/assistant-1/CARD/ORDER_STATUS/v1")
                .queryParam("expectedRevision", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.revision").value(2));
    }
}
