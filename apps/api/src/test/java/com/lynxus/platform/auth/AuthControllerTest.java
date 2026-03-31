package com.lynxus.platform.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserSession;
import com.lynxus.platform.shared.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {
    @Test
    void shouldReturnCurrentPlatformUserSession() throws Exception {
        AuthService authService = mock(AuthService.class);
        when(authService.currentSession()).thenReturn(new UserSession(
            "user-admin",
            "平台管理员",
            Role.PLATFORM_ADMIN,
            List.of(Role.PLATFORM_ADMIN)
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(authService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/api/auth/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.userId").value("user-admin"))
            .andExpect(jsonPath("$.data.displayName").value("平台管理员"))
            .andExpect(jsonPath("$.data.currentRole").value("PLATFORM_ADMIN"))
            .andExpect(jsonPath("$.data.availableRoles[0]").value("PLATFORM_ADMIN"));
    }

    @Test
    void shouldRedirectLoginToOidcAuthorizationEntry() throws Exception {
        AuthService authService = mock(AuthService.class);
        AuthRedirectSupport redirectSupport = mock(AuthRedirectSupport.class);
        when(redirectSupport.authorizationRequestPath()).thenReturn("/oauth2/authorization/lynxus");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(authService, redirectSupport))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/api/auth/login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/lynxus"));
    }

    @Test
    void shouldReturnPostLogoutRedirectUrl() throws Exception {
        AuthService authService = mock(AuthService.class);
        AuthRedirectSupport redirectSupport = mock(AuthRedirectSupport.class);
        when(redirectSupport.postLogoutRedirectUrl(any(), any())).thenReturn("/login");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(authService, redirectSupport))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(post("/api/auth/logout"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.postLogoutRedirectUrl").value("/login"));
    }

    private AuthController controller(AuthService authService) {
        return controller(authService, mock(AuthRedirectSupport.class));
    }

    private AuthController controller(AuthService authService, AuthRedirectSupport authRedirectSupport) {
        SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
        return new AuthController(
            authService,
            new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, "/"),
            authRedirectSupport,
            securityContextRepository
        );
    }
}
