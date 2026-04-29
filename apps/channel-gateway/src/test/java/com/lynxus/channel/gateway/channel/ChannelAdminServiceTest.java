package com.lynxus.channel.gateway.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lynxus.channel.gateway.shared.ConflictException;
import com.lynxus.channel.gateway.testing.EmbeddedPostgresTestDatabase;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChannelAdminServiceTest {
    private static EmbeddedPostgresTestDatabase database;

    private ChannelAdminService service;

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
        service = new ChannelAdminService(new ChannelAdminRepository(database.dsl(), new ObjectMapper()));
    }

    @Test
    void shouldPersistMaterializedAccountSnapshotAndRevision() {
        ChannelGatewayProfile created = service.createProfile(new CreateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人",
            ChannelProfileStatus.ACTIVE,
            true,
            Map.of("appId", "cli_xxx"),
            new ChannelAssistantBinding("assistant-1", null),
            new ChannelProfileAccountSnapshot("integration-account-1", "vault://opaque-ref")
        ));

        assertEquals(1, created.revision());
        assertEquals("integration-account-1", created.accountId());
        assertTrue(created.hasExternalSecretRef());

        ChannelGatewayProfile updated = service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人 Updated",
            ChannelProfileStatus.INACTIVE,
            false,
            Map.of("appId", "cli_xxx"),
            new ChannelAssistantBinding("assistant-2", "scenario-1"),
            new ChannelProfileAccountSnapshot("integration-account-2", null),
            1L
        ));

        assertEquals(2, updated.revision());
        assertEquals("飞书客服机器人 Updated", updated.displayName());
        assertEquals("integration-account-2", updated.accountId());
        assertEquals(false, updated.hasExternalSecretRef());
        assertEquals("assistant-2", updated.assistantBinding().assistantId());
    }

    @Test
    void shouldRejectStaleProfileRevision() {
        ChannelGatewayProfile created = service.createProfile(new CreateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人",
            null,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null
        ));

        service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人 Updated",
            null,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null,
            1L
        ));

        assertThrows(ConflictException.class, () -> service.updateProfile(created.id(), new UpdateChannelProfileInternalRequest(
            "feishu",
            "飞书客服机器人 Stale",
            null,
            true,
            Map.of("appId", "cli_xxx"),
            null,
            null,
            1L
        )));
    }
}
