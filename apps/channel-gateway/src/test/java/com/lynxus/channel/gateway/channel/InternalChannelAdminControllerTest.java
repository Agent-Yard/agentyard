package com.lynxus.channel.gateway.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import java.time.Instant;
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
}
