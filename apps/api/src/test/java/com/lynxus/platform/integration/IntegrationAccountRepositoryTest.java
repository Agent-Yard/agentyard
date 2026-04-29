package com.lynxus.platform.integration;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.shared.ConflictException;
import com.lynxus.platform.testing.EmbeddedPostgresTestDatabase;
import java.time.Instant;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IntegrationAccountRepositoryTest {
    private static EmbeddedPostgresTestDatabase database;

    private IntegrationAccountRepository repository;

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
        repository = new IntegrationAccountRepository(database.dsl(), new ObjectMapper());
    }

    @Test
    void acquireCredentialLifecycleLockUsesNowaitRowLock() {
        StoredIntegrationAccount account = storedAccount("integration-account-lock");
        insertAccount(account);

        DSLContext lockOwner = org.jooq.impl.DSL.using(database.dataSource(), SQLDialect.POSTGRES);
        IntegrationAccountRepository competitor = new IntegrationAccountRepository(
            org.jooq.impl.DSL.using(database.dataSource(), SQLDialect.POSTGRES),
            new ObjectMapper()
        );

        lockOwner.transaction(configuration -> {
            org.jooq.impl.DSL.using(configuration)
                .select(field(name("id"), String.class))
                .from(table(name("integration_account")))
                .where(field(name("id"), String.class).eq(account.id()))
                .forUpdate()
                .fetchOptional();

            ConflictException error = assertThrows(
                ConflictException.class,
                () -> competitor.acquireCredentialLifecycleLock(account.id())
            );
            assertEquals("integration account credential lifecycle operation is already running", error.getMessage());
        });

        assertDoesNotThrow(() -> competitor.acquireCredentialLifecycleLock(account.id()));
    }

    private static StoredIntegrationAccount storedAccount(String id) {
        Instant now = Instant.parse("2026-04-29T00:00:00Z");
        return new StoredIntegrationAccount(
            id,
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            "vault://existing-ref",
            null,
            null,
            IntegrationAccountCredentialStatus.ACTIVE,
            Map.of(),
            now,
            now
        );
    }

    private void insertAccount(StoredIntegrationAccount account) {
        database.dsl()
            .insertInto(table(name("integration_account")))
            .set(field(name("id"), String.class), account.id())
            .set(field(name("subject_type"), String.class), account.subjectType().name())
            .set(field(name("subject_id"), String.class), account.subjectId())
            .set(field(name("name"), String.class), account.name())
            .set(field(name("status"), String.class), account.status().name())
            .set(field(name("config"), JSONB.class), JSONB.valueOf("{}"))
            .set(field(name("external_secret_ref"), String.class), account.externalSecretRef())
            .set(field(name("credential_ciphertext"), String.class), account.credentialCiphertext())
            .set(field(name("credential_fingerprint"), String.class), account.credentialFingerprint())
            .set(field(name("credential_status"), String.class), account.credentialStatus().name())
            .set(field(name("metadata"), JSONB.class), JSONB.valueOf("{}"))
            .set(
                field(name("created_at"), java.time.OffsetDateTime.class),
                java.time.OffsetDateTime.parse("2026-04-29T00:00:00Z")
            )
            .set(
                field(name("updated_at"), java.time.OffsetDateTime.class),
                java.time.OffsetDateTime.parse("2026-04-29T00:00:00Z")
            )
            .execute();
    }
}
