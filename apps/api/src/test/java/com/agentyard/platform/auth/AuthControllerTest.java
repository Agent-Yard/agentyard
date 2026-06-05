package com.agentyard.platform.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserSession;
import com.agentyard.platform.shared.ApiExceptionHandler;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
        when(redirectSupport.authorizationRequestPath()).thenReturn("/oauth2/authorization/agentyard");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller(authService, redirectSupport))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        mockMvc.perform(get("/api/auth/login"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/agentyard"));
    }

    @Test
    void shouldStoreSafeLoginReturnToBeforeRedirectingToOidc() throws Exception {
        MockMvc mockMvc = mockMvcWithRealRedirectSupport();

        MvcResult result = mockMvc.perform(get("/api/auth/login")
                .param("returnTo", "/console/assistants?filter=active#details"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/agentyard"))
            .andReturn();

        Assertions.assertThat(result.getRequest().getSession(false))
            .isNotNull()
            .extracting(session -> session.getAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE))
            .isEqualTo("/console/assistants?filter=active#details");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/",
        "/console",
        "/console/runs?status=active#latest"
    })
    void shouldStoreAllowedLoginReturnToTargets(String returnTo) throws Exception {
        MockMvc mockMvc = mockMvcWithRealRedirectSupport();

        MvcResult result = mockMvc.perform(get("/api/auth/login").param("returnTo", returnTo))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/agentyard"))
            .andReturn();

        Assertions.assertThat(result.getRequest().getSession(false))
            .isNotNull()
            .extracting(session -> session.getAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE))
            .isEqualTo(returnTo);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "https://evil.example/console",
        "//evil.example/console",
        "/api/auth/session",
        "/oauth2/authorization/agentyard",
        "/login/oauth2/code/agentyard",
        "/login",
        "/consoleevil",
        "/console\n/next",
        "/console/../api/auth/session",
        "/console/%2e%2e/api/auth/session",
        "/console\\evil",
        "/console/%5Cevil",
        "/console/%00"
    })
    void shouldFallBackForUnsafeLoginReturnTo(String returnTo) throws Exception {
        MockMvc mockMvc = mockMvcWithRealRedirectSupport();

        MvcResult result = mockMvc.perform(get("/api/auth/login").param("returnTo", returnTo))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/oauth2/authorization/agentyard"))
            .andReturn();

        Assertions.assertThat(result.getRequest().getSession(false))
            .isNotNull()
            .extracting(session -> session.getAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE))
            .isEqualTo("/");
    }

    @Test
    void shouldRedirectDevBootstrapLoginToSafeReturnTo() throws Exception {
        MockMvc mockMvc = mockMvcWithRealRedirectSupport();

        mockMvc.perform(get("/api/auth/dev-bootstrap-login")
                .param("returnTo", "/console/runs?status=active#latest"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/console/runs?status=active#latest"));
    }

    @Test
    void shouldRedirectDevBootstrapLoginToLoginSuccessPathForUnsafeReturnTo() throws Exception {
        MockMvc mockMvc = mockMvcWithRealRedirectSupport();

        mockMvc.perform(get("/api/auth/dev-bootstrap-login")
                .param("returnTo", "https://evil.example.test/console"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/"));
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

    private MockMvc mockMvcWithRealRedirectSupport() {
        return MockMvcBuilders.standaloneSetup(controller(mock(AuthService.class), realRedirectSupport()))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    }

    private AuthRedirectSupport realRedirectSupport() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientRegistrationRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("agentyard")
            .clientId("client-id")
            .clientSecret("client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
            .scope("openid")
            .authorizationUri("https://idp.example.test/oauth2/authorize")
            .tokenUri("https://idp.example.test/oauth2/token")
            .jwkSetUri("https://idp.example.test/oauth2/jwks")
            .userInfoUri("https://idp.example.test/oauth2/userinfo")
            .userNameAttributeName(IdTokenClaimNames.SUB)
            .clientName("AgentYard")
            .build()));
        return new AuthRedirectSupport(
            new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, "/"),
            provider
        );
    }
}
