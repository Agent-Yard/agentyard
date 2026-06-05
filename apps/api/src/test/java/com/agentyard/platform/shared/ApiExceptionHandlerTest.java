package com.agentyard.platform.shared;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {
    @Test
    void shouldPreserveDownstreamStatusAndDetail() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DownstreamErrorController())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/test/downstream-error"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.title").value("Bad Gateway"))
            .andExpect(jsonPath("$.detail").value("channel gateway unavailable"));
    }

    @RestController
    static class DownstreamErrorController {
        @GetMapping("/test/downstream-error")
        String downstreamError() {
            throw new DownstreamServiceException(HttpStatus.BAD_GATEWAY, "channel gateway unavailable");
        }
    }
}
