package com.lynxus.platform.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserSession;
import com.lynxus.platform.shared.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthSecurityConfigurationTest {
    @Test
    void shouldRejectUnauthenticatedSessionRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            FilterChainProxy securityFilter = new FilterChainProxy(context.getBeansOfType(SecurityFilterChain.class).values().stream().toList());
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(AuthController.class))
                .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
                .addFilters(securityFilter)
                .build();

            mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
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
            FilterChainProxy securityFilter = new FilterChainProxy(context.getBeansOfType(SecurityFilterChain.class).values().stream().toList());
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(AuthController.class))
                .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
                .addFilters(securityFilter)
                .build();

            mockMvc.perform(get("/api/auth/session").with(user("admin")))
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
            FilterChainProxy securityFilter = new FilterChainProxy(context.getBeansOfType(SecurityFilterChain.class).values().stream().toList());
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(context.getBean(AuthController.class))
                .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
                .addFilters(securityFilter)
                .build();

            mockMvc.perform(post("/api/auth/logout").with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postLogoutRedirectUrl").value("/login"));
        }
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
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }
}
