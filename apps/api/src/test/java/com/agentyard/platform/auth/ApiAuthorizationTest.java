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
import com.agentyard.platform.agent.AgentController;
import com.agentyard.platform.agent.AgentService;
import com.agentyard.platform.agent.AgentHistoryClient;
import com.agentyard.platform.catalog.CatalogAccess;
import com.agentyard.platform.task.CollaborationService;
import com.agentyard.contracts.agent.AgentContracts;
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
    void shouldHideGovernanceCatalogFromBusinessUsers() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            mockMvc(context).perform(get("/api/role-templates").with(user("business"))).andExpect(status().isForbidden());
            mockMvc(context).perform(get("/api/catalog/summary").with(user("business"))).andExpect(status().isForbidden());
        }
    }
    @Test
    void shouldAllowBusinessUserToDiscoverPermittedEntriesAndSendMessages() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            var agents = context.getBean(AgentService.class);
            when(agents.entries()).thenReturn(List.of());
            when(agents.send(any())).thenReturn(new AgentContracts.Message("message-1","person-1","agent-1",null,null,
                AgentContracts.MessageKind.MESSAGE,java.util.Map.of("text","hello"),Instant.parse("2026-04-01T00:00:00Z")));
            var mvc=mockMvc(context);
            mvc.perform(get("/api/agent/entries").with(user("business"))).andExpect(status().isOk());
            mvc.perform(post("/api/agent/messages").with(user("business")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"requestId":"request-1","recipientId":"agent-1","kind":"MESSAGE","body":{"text":"hello"}}
                  """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("message-1"));
        }
    }
    @Test
    void shouldAllowDeveloperToCreateOwnTemplateButDenyAnotherPersonsOwnership() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            var catalog=context.getBean(CatalogService.class);
            when(catalog.createRoleTemplate(any())).thenReturn(new CatalogDtos.RoleTemplateDto("template-1","Research","Research",
                "PERSON","user-dev",null,null,List.of()));
            var mvc=mockMvc(context);
            mvc.perform(post("/api/role-templates").with(user("developer")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"name":"Research","description":"Research","ownerType":"PERSON","ownerId":"user-dev"}
                  """)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("template-1"));
            mvc.perform(post("/api/role-templates").with(user("developer")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"name":"Research","description":"Research","ownerType":"PERSON","ownerId":"someone-else"}
                  """)).andExpect(status().isForbidden());
        }
    }
    @Test
    void shouldAllowOnlyPlatformAdminToDeployEntryGrants() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            String entry="""
              {"name":"Reception","description":"Entry","ownerType":"PLATFORM","ownerId":"platform","shareScope":"SHARED",
               "receptionTemplateVersionId":"version-1","enabled":true,"allowedTemplateIds":[],
               "capabilityGrants":[{"operation":"tool.invoke","scopeKind":"PLATFORM","scopeId":"*","delegable":true}]}
              """;
            var mvc=mockMvc(context);
            for(String principal:List.of("business","developer")) mvc.perform(post("/api/entries").with(user(principal))
                .contentType(MediaType.APPLICATION_JSON).content(entry)).andExpect(status().isForbidden());
            mvc.perform(post("/api/entries").with(user("admin")).contentType(MediaType.APPLICATION_JSON).content(entry))
                .andExpect(status().isOk());
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

            mockMvc.perform(delete("/api/channel-admin/profiles/channel-profile-1/template-bindings/entry-1/CARD/ORDER_STATUS/v1")
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
    void shouldRestrictGlobalPlatformEventsToAdministrator() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class, AuthSecurityConfiguration.class)) {
            PlatformEventService platformEventService = context.getBean(PlatformEventService.class);
            when(platformEventService.listEvents(any(), any(), any(), any(), any())).thenReturn(new PlatformEventDtos.PlatformEventPageDto(List.of(), null));

            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/api/events").with(user("developer"))).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/events").with(user("admin")))
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
                context.getBean(AgentController.class),
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
        CatalogController catalogController(CatalogService catalogService, CatalogAccess catalogAccess) {
            return new CatalogController(catalogService,catalogAccess);
        }

        @Bean
        ChannelAdminController channelAdminController(ChannelAdminService channelAdminService) {
            return new ChannelAdminController(channelAdminService);
        }

        @Bean AgentController agentController(AgentService agents,AgentHistoryClient history) { return new AgentController(agents,history); }
        @Bean CatalogAccess catalogAccess(CurrentUserResolver users,CollaborationService collaboration,CatalogService catalog) { return new CatalogAccess(users,collaboration,catalog); }
        @Bean CollaborationService collaborationService() { return mock(CollaborationService.class); }

        @Bean
        PlatformEventController platformEventController(PlatformEventService platformEventService, CatalogAccess access) {
            return new PlatformEventController(platformEventService, access);
        }

        @Bean
        CatalogService catalogService() {
            return mock(CatalogService.class);
        }

        @Bean
        ChannelAdminService channelAdminService() {
            return mock(ChannelAdminService.class);
        }

        @Bean AgentService agentService() { return mock(AgentService.class); }
        @Bean AgentHistoryClient agentHistoryClient() { return mock(AgentHistoryClient.class); }

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
