package com.agentyard.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserSession;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import com.agentyard.platform.shared.ApiExceptionHandler;
import com.agentyard.platform.shared.logging.ApiLogContextFilter;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class AuthSecurityConfigurationTest {
    @Test
    void shouldRejectUnauthenticatedSessionRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            assertUnauthenticatedSessionRequestReturnsProblem(mockMvc(context));
        }
    }

    @Test
    void shouldRejectUnauthenticatedSessionRequestWithOAuth2Client() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
            TestConfig.class,
            OAuth2ClientRegistrationTestConfig.class,
            AuthSecurityConfiguration.class
        )) {
            assertUnauthenticatedSessionRequestReturnsProblem(mockMvc(context));
        }
    }

    @Test
    void shouldAllowAuthenticatedSessionRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            AuthService authService = context.getBean(AuthService.class);
            when(authService.currentSession()).thenReturn(new UserSession(
                "user-admin",
                "平台管理员",
                Role.PLATFORM_ADMIN,
                List.of(Role.PLATFORM_ADMIN)
            ));

            mockMvc(context).perform(get("/api/auth/session").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("user-admin"));
        }
    }

    @Test
    void shouldAllowAuthenticatedLogoutWhenSessionLookupFails() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            AuthService authService = context.getBean(AuthService.class);
            AuthRedirectSupport authRedirectSupport = context.getBean(AuthRedirectSupport.class);
            when(authService.currentSession()).thenThrow(new IllegalStateException("current user is disabled: admin"));
            when(authRedirectSupport.postLogoutRedirectUrl(any(), any())).thenReturn("/login");

            mockMvc(context).perform(post("/api/auth/logout").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postLogoutRedirectUrl").value("/login"));
        }
    }

    @Test
    void shouldLogOAuth2AuthenticationFailure(CapturedOutput output) throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
            TestConfig.class,
            OAuth2ClientRegistrationTestConfig.class,
            AuthSecurityConfiguration.class
        )) {
            mockMvc(context).perform(get("/login/oauth2/code/agentyard")
                    .param("error", "access_denied")
                    .param("error_description", "Provider rejected login\nwith newline")
                    .param("state", "secret-state"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error"));

            assertThat(output)
                .contains("oauth2 authentication failed")
                .contains("registrationId=agentyard")
                .contains("errorCode=authorization_request_not_found")
                .contains("responseErrorCode=access_denied")
                .contains("responseErrorDescription=Provider rejected login with newline")
                .doesNotContain("secret-state");
        }
    }

    private void assertUnauthenticatedSessionRequestReturnsProblem(MockMvc mockMvc) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/session"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Authentication is required"))
            .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    private MockMvc mockMvc(AnnotationConfigApplicationContext context) {
        FilterChainProxy securityFilter = new FilterChainProxy(
            context.getBeansOfType(SecurityFilterChain.class).values().stream().toList()
        );
        return MockMvcBuilders.standaloneSetup(context.getBean(AuthController.class))
            .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
            .addFilters(securityFilter)
            .build();
    }

    @Configuration
    @EnableWebSecurity
    static class TestConfig {
        @Bean
        AuthController authController(AuthService authService, AuthProperties authProperties, AuthRedirectSupport authRedirectSupport) {
            return new AuthController(
                authService,
                authProperties,
                authRedirectSupport,
                new HttpSessionSecurityContextRepository()
            );
        }

        @Bean
        AuthService authService() {
            return mock(AuthService.class);
        }

        @Bean
        PlatformAuthorization platformAuthorization(AuthService authService) {
            return new PlatformAuthorization(authService);
        }

        @Bean
        AuthProperties authProperties() {
            return new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, "/");
        }

        @Bean
        AuthRedirectSupport authRedirectSupport() {
            return mock(AuthRedirectSupport.class);
        }

        @Bean
        OidcProvisioningSuccessHandler oidcProvisioningSuccessHandler() {
            return mock(OidcProvisioningSuccessHandler.class);
        }

        @Bean
        CurrentUserResolver currentUserResolver() {
            Instant now = Instant.parse("2026-04-01T00:00:00Z");
            return () -> new PlatformUser(
                "user-admin",
                "admin",
                "平台管理员",
                "admin@agentyard.local",
                AuthSource.LOCAL_BOOTSTRAP,
                null,
                null,
                UserStatus.ACTIVE,
                now,
                now,
                now,
                List.of(Role.PLATFORM_ADMIN)
            );
        }

        @Bean
        ApiLogContextFilter apiLogContextFilter(CurrentUserResolver currentUserResolver, ObjectMapper objectMapper) {
            return new ApiLogContextFilter(currentUserResolver, objectMapper);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @Configuration
    static class OAuth2ClientRegistrationTestConfig {
        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("agentyard")
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
                .build());
        }
    }
}
