package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.channel.gateway.channel.ChannelAdminService;
import com.lynxus.channel.gateway.channel.ChannelInboundSessionDispatcher;
import com.lynxus.channel.gateway.channel.ChannelSessionRuntimeClient;
import com.lynxus.channel.gateway.channel.NormalizedChannelTurnIngestService;
import com.lynxus.channel.gateway.extension.ChannelGatewayDescriptorProvider;
import com.lynxus.channel.gateway.extension.ChannelProviderRegistryLoader;
import com.lynxus.channel.gateway.extension.ExtensionManifestFetcher;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationProperties;
import com.lynxus.channel.gateway.extension.ExtensionRegistrationService;
import com.lynxus.channel.gateway.extension.RuntimeChannelProviderRegistry;
import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.session.SessionContracts.AcceptedSessionMessageAllocation;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnRequest;
import com.lynxus.contracts.session.SessionContracts.ChannelInboundSessionTurnResponse;
import com.lynxus.contracts.session.SessionContracts.SessionMessageDeliveryStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_TURN;

class FeishuWebhookControllerTest {
    private static EmbeddedPostgresTestDatabase database;

    private MockMvc mockMvc;
    private ChannelAdminRepository repository;
    private FakeFeishuIntegrationAccountRuntimeProvider accountRuntimeProvider;
    private CapturingRuntimeClient runtimeClient;

    @BeforeAll
    static void startDatabase() throws Exception {
        database = new EmbeddedPostgresTestDatabase();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database.reset();
        ObjectMapper objectMapper = new ObjectMapper();
        repository = new ChannelAdminRepository(database.dsl(), objectMapper);
        ChannelAdminService channelAdminService = new ChannelAdminService(repository, coreRegistry());
        accountRuntimeProvider = new FakeFeishuIntegrationAccountRuntimeProvider();
        accountRuntimeProvider.accounts = Map.of(
            "integration-account-1",
            new FeishuIntegrationAccountRuntime(
                "integration-account-1",
                "CHANNEL_PROVIDER",
                "feishu",
                "ENABLED",
                Map.of("appId", "cli_xxx"),
                Map.of("verificationToken", "verify-token")
            )
        );
        runtimeClient = new CapturingRuntimeClient();
        FeishuWebhookService feishuWebhookService = new FeishuWebhookService(
            channelAdminService,
            objectMapper,
            accountRuntimeProvider,
            new NormalizedChannelTurnIngestService(repository, registrationService()),
            new ChannelInboundSessionDispatcher(repository, runtimeClient)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new FeishuWebhookController(feishuWebhookService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        channelAdminService.createProfile(new CreateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人",
            null,
            true,
            Map.of(),
            new ChannelAssistantBinding("assistant-1", null),
            new ChannelProfileAccountSnapshot("integration-account-1", null)
        ));
    }

    private static RuntimeChannelProviderRegistry coreRegistry() {
        ExtensionManifestFetcher fetcher = (manifestUrl, headers) -> {
            throw new AssertionError("core channel gateway registry must not fetch self HTTP");
        };
        ExtensionRegistrationService registrationService = new ExtensionRegistrationService(
            new ExtensionRegistrationProperties(null),
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        );
        return new RuntimeChannelProviderRegistry(new ChannelProviderRegistryLoader(
            registrationService,
            new ChannelGatewayDescriptorProvider(),
            fetcher,
            "internal-token"
        ));
    }

    @Test
    void shouldReturnChallengeForFeishuUrlVerification() throws Exception {
        mockMvc.perform(post("/connectors/feishu/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type": "url_verification",
                      "app_id": "cli_xxx",
                      "token": "verify-token",
                      "challenge": "challenge-value"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.challenge").value("challenge-value"));
    }

    @Test
    void shouldRejectWebhookWhenConfiguredVerificationTokenIsMissing() throws Exception {
        mockMvc.perform(post("/connectors/feishu/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type": "url_verification",
                      "app_id": "cli_xxx",
                      "challenge": "challenge-value"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("feishu verification token mismatch"));
    }

    @Test
    void shouldAcceptWebhookWhenIntegrationAccountHasNoVerificationToken() throws Exception {
        accountRuntimeProvider.accounts = Map.of(
            "integration-account-1",
            new FeishuIntegrationAccountRuntime(
                "integration-account-1",
                "CHANNEL_PROVIDER",
                "feishu",
                "ENABLED",
                Map.of("appId", "cli_xxx"),
                Map.of()
            )
        );

        mockMvc.perform(post("/connectors/feishu/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "type": "url_verification",
                      "app_id": "cli_xxx",
                      "challenge": "challenge-value"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.challenge").value("challenge-value"));
    }

    @Test
    void shouldPersistInboundEventAndDeduplicateRepeatedWebhook() throws Exception {
        String payload = """
            {
              "schema": "2.0",
              "header": {
                "app_id": "cli_xxx",
                "event_type": "im.message.receive_v1",
                "event_id": "evt_001"
              },
              "event": {
                "open_chat_id": "oc_123",
                "sender": {
                  "sender_id": {
                    "open_id": "ou_123",
                    "user_id": "user_123"
                  }
                },
                "message": {
                  "message_id": "om_123",
                  "content": "{\\"text\\":\\"hello\\"}"
                }
              },
              "token": "verify-token"
            }
            """;

        mockMvc.perform(post("/connectors/feishu/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Lark-Request-Timestamp", "1710000000")
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISPATCHED"))
            .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/connectors/feishu/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Lark-Request-Timestamp", "1710000000")
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISPATCHED"))
            .andExpect(jsonPath("$.duplicate").value(true));

        String channelProfileId = repository.listProfiles().getFirst().id();
        assertEquals(0, repository.listInboundEvents(channelProfileId).size());
        assertEquals(1, database.dsl().fetchCount(CHANNEL_INBOUND_TURN));
        assertEquals("user_123", runtimeClient.requests.getFirst().messages().getFirst().sender().senderName());
    }

    private static ExtensionRegistrationService registrationService() {
        return new ExtensionRegistrationService(
            new ExtensionRegistrationProperties(null),
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        );
    }

    private static final class CapturingRuntimeClient implements ChannelSessionRuntimeClient {
        private final List<ChannelInboundSessionTurnRequest> requests = new java.util.ArrayList<>();

        @Override
        public ChannelInboundSessionTurnResponse dispatchInboundTurn(ChannelInboundSessionTurnRequest request) {
            requests.add(request);
            return new ChannelInboundSessionTurnResponse(
                "session-feishu",
                "turn-session-feishu",
                SessionMessageDeliveryStatus.ACCEPTED,
                List.of("session-message-feishu"),
                List.of(new AcceptedSessionMessageAllocation(0, null, "session-message-feishu", 0)),
                List.of(),
                null
            );
        }
    }

    private static final class FakeFeishuIntegrationAccountRuntimeProvider implements FeishuIntegrationAccountRuntimeProvider {
        private Map<String, FeishuIntegrationAccountRuntime> accounts = Map.of();

        @Override
        public FeishuIntegrationAccountRuntime load(String accountId) {
            FeishuIntegrationAccountRuntime account = accounts.get(accountId);
            if (account == null) {
                throw new IllegalArgumentException("unknown account: " + accountId);
            }
            return account;
        }
    }
}
