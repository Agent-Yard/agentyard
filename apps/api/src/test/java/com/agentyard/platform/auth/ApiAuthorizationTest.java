package com.agentyard.platform.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import com.agentyard.platform.shared.logging.ApiLogContextFilter;
import com.agentyard.platform.channel.ChannelAdminController;
import com.agentyard.platform.channel.ChannelAdminService;
import com.agentyard.platform.catalog.CatalogController;
import com.agentyard.platform.catalog.CatalogDtos;
import com.agentyard.platform.catalog.CatalogService;
import com.agentyard.platform.event.PlatformEventController;
import com.agentyard.platform.event.PlatformEventDtos;
import com.agentyard.platform.event.PlatformEventService;
import com.agentyard.platform.session.SessionRuntimeController;
import com.agentyard.platform.session.SessionRuntimeDtos;
import com.agentyard.platform.session.SessionRuntimeService;
import com.agentyard.platform.session.SessionRuntimeStreamService;
import com.agentyard.platform.shared.ApiExceptionHandler;
import com.agentyard.contracts.channel.ChannelContracts;
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
            SessionRuntimeService sessionRuntimeService = context.getBean(SessionRuntimeService.class);
            when(sessionRuntimeService.sendTurn(any(), any())).thenReturn(new com.agentyard.contracts.session.SessionContracts.SendSessionTurnResponse(
                "session-1",
                "turn-1",
                com.agentyard.contracts.session.SessionContracts.SessionMessageDeliveryStatus.ACCEPTED,
                java.util.List.of("message-1"),
                java.util.List.of(new com.agentyard.contracts.session.SessionContracts.AcceptedSessionMessageAllocation(0, "draft-1", "message-1", 0)),
                java.util.List.of(),
                null
            ));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/api/session-runtime/turns")
                    .with(user("business"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "turn-1")
                    .content("""
                        {
                          "assistantId": "assistant-1",
                          "customerId": "customer-1",
                          "turnDedupKey": "turn-1",
                          "messages": [
                            {
                              "clientMessageId": "draft-1",
                              "blocks": [
                                {
                                  "type": "TEXT",
                                  "text": "你好"
                                }
                              ],
                              "metadata": {}
                            }
                          ],
                          "metadata": {}
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("session-1"));
        }
    }

    @Test
    void shouldRejectBusinessUserChannelAdminReadRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/api/channel-admin/profiles").with(user("business")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));
        }
    }

    @Test
    void shouldAllowDeveloperChannelAdminWriteRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            ChannelAdminService channelAdminService = context.getBean(ChannelAdminService.class);
            when(channelAdminService.createProfile(any())).thenReturn(new ChannelContracts.ChannelProfile(
                "channel-profile-1",
                "feishu",
                "飞书客服机器人",
                ChannelContracts.ChannelProfileStatus.ACTIVE,
                true,
                java.util.Map.of(),
                null,
                null,
                false,
                1,
                null,
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z")
            ));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/api/channel-admin/profiles")
                    .with(user("developer"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "providerType": "feishu",
                          "displayName": "飞书客服机器人",
                          "status": "ACTIVE",
                          "inboundEnabled": true,
                          "config": {}
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("channel-profile-1"));
        }
    }

    @Test
    void shouldRequireGovernanceWriteForChannelAdminDeleteRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(delete("/api/channel-admin/profiles/channel-profile-1")
                    .queryParam("expectedRevision", "1")
                    .with(user("business")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));
        }
    }

    @Test
    void shouldRequireGovernanceWriteForChannelProviderManualRun() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/api/channel-admin/profiles/channel-profile-1/jobs/PULL_MESSAGES/runs")
                    .with(user("business")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));
        }
    }

    @Test
    void shouldRequireGovernanceWriteForTemplateBindingDeleteRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(delete("/api/channel-admin/profiles/channel-profile-1/template-bindings/assistant-1/CARD/ORDER_STATUS/v1")
                    .queryParam("expectedRevision", "1")
                    .with(user("business")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));
        }
    }

    @Test
    void shouldAllowDeveloperChannelAdminDeleteRequest() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            ChannelAdminService channelAdminService = context.getBean(ChannelAdminService.class);
            when(channelAdminService.deleteProfile("channel-profile-1", 1L)).thenReturn(new ChannelContracts.ChannelProfile(
                "channel-profile-1",
                "feishu",
                "飞书客服机器人",
                ChannelContracts.ChannelProfileStatus.INACTIVE,
                true,
                java.util.Map.of(),
                null,
                null,
                false,
                2,
                null,
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:01:00Z")
            ));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(delete("/api/channel-admin/profiles/channel-profile-1")
                    .queryParam("expectedRevision", "1")
                    .with(user("developer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"))
                .andExpect(jsonPath("$.data.revision").value(2));
        }
    }

    @Test
    void shouldNotExposeOldChannelAdminAccountsPath() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/api/channel-admin/accounts").with(user("developer")))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void shouldRejectBusinessUserPlatformEventsQuery() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/api/events").with(user("business")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Access is denied"));
        }
    }

    @Test
    void shouldAllowDeveloperPlatformEventsQuery() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            PlatformEventService platformEventService = context.getBean(PlatformEventService.class);
            when(platformEventService.listEvents(any(), any(), any(), any(), any())).thenReturn(new PlatformEventDtos.PlatformEventPageDto(List.of(), null));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/api/events").with(user("developer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }
    }

    private MockMvc mockMvc(AnnotationConfigApplicationContext context) {
        FilterChainProxy securityFilter = new FilterChainProxy(context.getBeansOfType(SecurityFilterChain.class).values().stream().toList());
        return MockMvcBuilders
            .standaloneSetup(
                context.getBean(CatalogController.class),
                context.getBean(ChannelAdminController.class),
                context.getBean(SessionRuntimeController.class),
                context.getBean(PlatformEventController.class)
            )
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
        ChannelAdminController channelAdminController(ChannelAdminService channelAdminService) {
            return new ChannelAdminController(channelAdminService);
        }

        @Bean
        SessionRuntimeController sessionRuntimeController(
            SessionRuntimeService sessionRuntimeService,
            SessionRuntimeStreamService sessionRuntimeStreamService,
            CurrentUserResolver currentUserResolver
        ) {
            return new SessionRuntimeController(sessionRuntimeService, sessionRuntimeStreamService, currentUserResolver);
        }

        @Bean
        PlatformEventController platformEventController(PlatformEventService platformEventService) {
            return new PlatformEventController(platformEventService);
        }

        @Bean
        CatalogService catalogService() {
            return mock(CatalogService.class);
        }

        @Bean
        ChannelAdminService channelAdminService() {
            return mock(ChannelAdminService.class);
        }

        @Bean
        SessionRuntimeService sessionRuntimeService() {
            return mock(SessionRuntimeService.class);
        }

        @Bean
        SessionRuntimeStreamService sessionRuntimeStreamService() {
            return mock(SessionRuntimeStreamService.class);
        }

        @Bean
        PlatformEventService platformEventService() {
            return mock(PlatformEventService.class);
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
                username + "@agentyard.local",
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
