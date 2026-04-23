package com.lynxus.channel.gateway.connector.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.channel.gateway.channel.ChannelAdminService;
import com.lynxus.channel.gateway.shared.ApiExceptionHandler;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelAccountRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderType;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class FeishuWebhookControllerTest {
    private static EmbeddedPostgresTestDatabase database;

    private MockMvc mockMvc;
    private ChannelAdminRepository repository;

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
        ChannelAdminService channelAdminService = new ChannelAdminService(repository);
        FeishuWebhookService feishuWebhookService = new FeishuWebhookService(channelAdminService, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new FeishuWebhookController(feishuWebhookService))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

        channelAdminService.createAccount(new CreateChannelAccountRequest(
            ChannelProviderType.FEISHU,
            "飞书客服机器人",
            null,
            Map.of("appId", "cli_xxx", "verificationToken", "verify-token")
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
                    "open_id": "ou_123"
                  }
                },
                "message": {
                  "message_id": "om_123"
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
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.duplicate").value(false));

        mockMvc.perform(post("/connectors/feishu/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Lark-Request-Timestamp", "1710000000")
                .content(payload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.duplicate").value(true));

        String accountId = repository.listAccounts().getFirst().id();
        assertEquals(1, repository.listInboundEvents(accountId).size());
        assertEquals("evt_001", repository.listInboundEvents(accountId).getFirst().externalEventId());
    }
}
