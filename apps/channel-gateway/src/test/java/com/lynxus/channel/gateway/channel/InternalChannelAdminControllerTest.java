package com.lynxus.channel.gateway.channel;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
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
}
