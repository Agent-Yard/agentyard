package com.lynxus.channel.gateway.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.channel.ChannelAdminService;
import com.lynxus.channel.gateway.channel.ChannelOutboundExtensionAckService;
import com.lynxus.channel.gateway.channel.ChannelOutboundExtensionController;
import com.lynxus.channel.gateway.channel.ChannelOutboundExtensionStreamService;
import com.lynxus.channel.gateway.channel.ChannelOutboundExtensionSubscriptionService;
import com.lynxus.channel.gateway.channel.ChannelInboundSessionDispatcher;
import com.lynxus.channel.gateway.channel.InternalNormalizedChannelEventController;
import com.lynxus.channel.gateway.channel.InternalNormalizedChannelTurnController;
import com.lynxus.channel.gateway.channel.InternalChannelAdminController;
import com.lynxus.channel.gateway.channel.NormalizedChannelEventIngestService;
import com.lynxus.channel.gateway.channel.NormalizedChannelTurnIngestService;
import com.lynxus.channel.gateway.connector.feishu.FeishuWebhookController;
import com.lynxus.channel.gateway.connector.feishu.FeishuWebhookService;
import com.lynxus.channel.gateway.extension.ChannelGatewayDescriptorProvider;
import com.lynxus.channel.gateway.extension.ExtensionManifestController;
import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class InternalAuthSecurityConfigurationTest {
    @Test
    void shouldRejectStartupWithoutInternalAuthToken() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(MissingTokenTestConfig.class, InternalAuthSecurityConfiguration.class);
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class, context::refresh);
        }
    }

    @Test
    void shouldRejectInternalRouteWithoutBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/internal/channel-admin/profiles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
        }
    }

    @Test
    void shouldAllowInternalRouteWithValidBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            ChannelAdminService channelAdminService = context.getBean(ChannelAdminService.class);
            when(channelAdminService.listProfiles()).thenReturn(List.of());
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/internal/channel-admin/profiles").header("Authorization", "Bearer test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Test
    void shouldRejectNormalizedEventRouteWithoutBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/internal/channel-events/normalized")
                    .contentType("application/json")
                    .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
        }
    }

    @Test
    void shouldNotExposeOldInternalChannelAdminAccountsPath() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/internal/channel-admin/accounts").header("Authorization", "Bearer test-internal-token"))
                .andExpect(status().isNotFound());
        }
    }

    @Test
    void shouldAllowFeishuWebhookWithoutInternalBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            FeishuWebhookService feishuWebhookService = context.getBean(FeishuWebhookService.class);
            when(feishuWebhookService.handleWebhook(any(), any())).thenReturn(java.util.Map.of("challenge", "ok"));
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(post("/connectors/feishu/webhook")
                    .contentType("application/json")
                    .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("ok"));
        }
    }

    @Test
    void shouldRejectManifestWithoutBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/extension/manifest"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void shouldRejectManifestWithInvalidBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/extension/manifest").header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void shouldAllowManifestWithValidBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/extension/manifest").header("Authorization", "Bearer test-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.descriptors.channelProviders[0].providerType").value("feishu"));
        }
    }

    @Test
    void shouldRejectExtensionOutboundBootstrapWithoutBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/extension/channel/outbound-frame-subscriptions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication is required"));
        }
    }

    @Test
    void shouldAllowExtensionOutboundBootstrapWithValidBearerToken() throws Exception {
        try (AnnotationConfigApplicationContext context = createAuthorizedContext()) {
            ChannelOutboundExtensionSubscriptionService subscriptionService = context.getBean(ChannelOutboundExtensionSubscriptionService.class);
            when(subscriptionService.list(any())).thenReturn(new ChannelOutboundExtensionSubscriptionService.ChannelOutboundFrameSubscriptionList(List.of()));
            MockMvc mockMvc = mockMvc(context);

            mockMvc.perform(get("/extension/channel/outbound-frame-subscriptions")
                    .header("Authorization", "Bearer test-internal-token")
                    .header("X-Lynxus-Extension-Registration-Id", "acme-channel-provider")
                    .header("X-Lynxus-Extension-Descriptor-Type", "CHANNEL_PROVIDER")
                    .header("X-Lynxus-Extension-Descriptor-Id", "enterprise.acme.im"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions").isArray());
        }
    }

    private AnnotationConfigApplicationContext createAuthorizedContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
            "lynxus.internal-auth.token", "test-internal-token"
        )));
        context.register(TestConfig.class, InternalAuthSecurityConfiguration.class);
        context.refresh();
        return context;
    }

    private MockMvc mockMvc(AnnotationConfigApplicationContext context) {
        FilterChainProxy securityFilter = new FilterChainProxy(context.getBeansOfType(SecurityFilterChain.class).values().stream().toList());
        return MockMvcBuilders.standaloneSetup(
                context.getBean(InternalChannelAdminController.class),
                context.getBean(InternalNormalizedChannelEventController.class),
                context.getBean(InternalNormalizedChannelTurnController.class),
                context.getBean(FeishuWebhookController.class),
                context.getBean(ExtensionManifestController.class),
                context.getBean(ChannelOutboundExtensionController.class)
            )
            .setControllerAdvice(context.getBean(ApiExceptionHandler.class))
            .addFilters(securityFilter)
            .build();
    }

    @Configuration
    @EnableWebSecurity
    static class TestConfig {
        @Bean
        InternalChannelAdminController internalChannelAdminController(ChannelAdminService channelAdminService) {
            return new InternalChannelAdminController(channelAdminService);
        }

        @Bean
        InternalNormalizedChannelEventController internalNormalizedChannelEventController(
            NormalizedChannelEventIngestService ingestService,
            ObjectMapper objectMapper
        ) {
            return new InternalNormalizedChannelEventController(ingestService, objectMapper);
        }

        @Bean
        InternalNormalizedChannelTurnController internalNormalizedChannelTurnController(
            NormalizedChannelTurnIngestService ingestService,
            ChannelInboundSessionDispatcher dispatcher,
            ObjectMapper objectMapper
        ) {
            return new InternalNormalizedChannelTurnController(ingestService, dispatcher, objectMapper);
        }

        @Bean
        FeishuWebhookController feishuWebhookController(FeishuWebhookService feishuWebhookService) {
            return new FeishuWebhookController(feishuWebhookService);
        }

        @Bean
        ExtensionManifestController extensionManifestController(ChannelGatewayDescriptorProvider descriptorProvider) {
            return new ExtensionManifestController(descriptorProvider);
        }

        @Bean
        ChannelOutboundExtensionController channelOutboundExtensionController(
            ChannelOutboundExtensionSubscriptionService subscriptionService,
            ChannelOutboundExtensionStreamService streamService,
            ChannelOutboundExtensionAckService ackService
        ) {
            return new ChannelOutboundExtensionController(subscriptionService, streamService, ackService);
        }

        @Bean
        ChannelGatewayDescriptorProvider channelGatewayDescriptorProvider() {
            return new ChannelGatewayDescriptorProvider();
        }

        @Bean
        ChannelAdminService channelAdminService() {
            return mock(ChannelAdminService.class);
        }

        @Bean
        ChannelOutboundExtensionSubscriptionService channelOutboundExtensionSubscriptionService() {
            return mock(ChannelOutboundExtensionSubscriptionService.class);
        }

        @Bean
        ChannelOutboundExtensionStreamService channelOutboundExtensionStreamService() {
            return mock(ChannelOutboundExtensionStreamService.class);
        }

        @Bean
        ChannelOutboundExtensionAckService channelOutboundExtensionAckService() {
            return mock(ChannelOutboundExtensionAckService.class);
        }

        @Bean
        NormalizedChannelEventIngestService normalizedChannelEventIngestService() {
            return mock(NormalizedChannelEventIngestService.class);
        }

        @Bean
        NormalizedChannelTurnIngestService normalizedChannelTurnIngestService() {
            return mock(NormalizedChannelTurnIngestService.class);
        }

        @Bean
        ChannelInboundSessionDispatcher channelInboundSessionDispatcher() {
            return mock(ChannelInboundSessionDispatcher.class);
        }

        @Bean
        FeishuWebhookService feishuWebhookService() {
            return mock(FeishuWebhookService.class);
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
    @EnableWebSecurity
    static class MissingTokenTestConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
