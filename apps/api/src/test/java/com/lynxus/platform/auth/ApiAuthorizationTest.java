package com.lynxus.platform.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import com.lynxus.platform.shared.logging.ApiLogContextFilter;
import com.lynxus.platform.catalog.CatalogController;
import com.lynxus.platform.catalog.CatalogDtos;
import com.lynxus.platform.catalog.CatalogService;
import com.lynxus.platform.runtime.RuntimeController;
import com.lynxus.platform.runtime.RuntimeDtos;
import com.lynxus.platform.runtime.RuntimeService;
import com.lynxus.platform.shared.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiAuthorizationTest {
    @Test
    void shouldAllowBusinessUserToReadGovernanceData() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            CatalogService catalogService = context.getBean(CatalogService.class);
            when(catalogService.listDomains()).thenReturn(List.of());

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/api/domains").with(user("business")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Test
    void shouldRejectBusinessUserGovernanceWriteRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/api/domains")
                    .with(user("business"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name": "客户运营域",
                          "description": "面向客户运营治理"
                        }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));
        }
    }

    @Test
    void shouldAllowDeveloperGovernanceWriteRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            CatalogService catalogService = context.getBean(CatalogService.class);
            when(catalogService.createDomain(any())).thenReturn(new CatalogDtos.BusinessDomainDto(
                "domain-1",
                "客户运营域",
                "面向客户运营治理",
                List.of(),
                List.of(),
                List.of()
            ));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/api/domains")
                    .with(user("developer"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "name": "客户运营域",
                          "description": "面向客户运营治理"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("domain-1"));
        }
    }

    @Test
    void shouldAllowBusinessUserRuntimeRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            RuntimeService runtimeService = context.getBean(RuntimeService.class);
            when(runtimeService.createSession(any())).thenReturn(new RuntimeDtos.ConversationSessionDto(
                "session-1",
                "scenario-1",
                "默认会话",
                "tester",
                "assistant-1",
                "助手",
                "1.0.0",
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                com.lynxus.contracts.runtime.WorkflowContracts.SharedSessionState.empty()
            ));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/api/runtime/sessions")
                    .with(user("business"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "scenarioId": "scenario-1",
                          "assistantId": "assistant-1",
                          "customerId": "customer-1",
                          "openingMessage": {
                            "payloadType": "TEXT",
                            "payload": {
                              "text": "你好"
                            }
                          }
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("session-1"));
        }
    }

    private MockMvc mockMvc(AnnotationConfigApplicationContext context) {
        FilterChainProxy securityFilter = new FilterChainProxy(context.getBeansOfType(SecurityFilterChain.class).values().stream().toList());
        return MockMvcBuilders
            .standaloneSetup(context.getBean(CatalogController.class), context.getBean(RuntimeController.class))
            .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
            .addFilters(securityFilter)
            .build();
    }

    @Configuration
    @EnableWebSecurity
    static class TestConfig {
        @Bean
        CatalogController catalogController(CatalogService catalogService) {
            return new CatalogController(catalogService);
        }

        @Bean
        RuntimeController runtimeController(RuntimeService runtimeService) {
            return new RuntimeController(runtimeService);
        }

        @Bean
        CatalogService catalogService() {
            return mock(CatalogService.class);
        }

        @Bean
        RuntimeService runtimeService() {
            return mock(RuntimeService.class);
        }

        @Bean
        CurrentUserResolver currentUserResolver() {
            return () -> platformUser(SecurityContextHolder.getContext().getAuthentication().getName());
        }

        @Bean
        tools.jackson.databind.ObjectMapper objectMapper() {
            return new tools.jackson.databind.ObjectMapper();
        }

        @Bean
        ApiLogContextFilter apiLogContextFilter(CurrentUserResolver currentUserResolver, tools.jackson.databind.ObjectMapper objectMapper) {
            return new ApiLogContextFilter(currentUserResolver, objectMapper);
        }

        @Bean
        AuthService authService(CurrentUserResolver currentUserResolver) {
            return new AuthService(currentUserResolver);
        }

        @Bean
        PlatformAuthorization platformAuthorization(AuthService authService) {
            return new PlatformAuthorization(authService);
        }

        @Bean
        OidcProvisioningSuccessHandler oidcProvisioningSuccessHandler() {
            return mock(OidcProvisioningSuccessHandler.class);
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }

        private static PlatformUser platformUser(String username) {
            return switch (username) {
                case "developer" -> user("user-dev", username, Role.DEVELOPER);
                case "business" -> user("user-biz", username, Role.BUSINESS_USER);
                default -> user("user-admin", username, Role.PLATFORM_ADMIN);
            };
        }

        private static PlatformUser user(String id, String username, Role role) {
            Instant now = Instant.parse("2026-04-01T00:00:00Z");
            return new PlatformUser(
                id,
                username,
                username,
                username + "@lynxus.local",
                AuthSource.LOCAL_BOOTSTRAP,
                null,
                null,
                UserStatus.ACTIVE,
                now,
                now,
                now,
                List.of(role)
            );
        }
    }
}
