package com.lynxus.platform.integration;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.lynxus.contracts.runtime.WorkflowContracts.ToolConnectorType;
import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class IntegrationAccountRepository {
    private static final Table<?> INTEGRATION_ACCOUNT = table(name("integration_account"));
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;

    public IntegrationAccountRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.jsonbSupport = new JooqJsonbSupport(objectMapper);
    }

    public List<StoredIntegrationAccount> listAccounts() {
        return dsl.selectFrom(INTEGRATION_ACCOUNT)
            .orderBy(field(name("updated_at")).desc())
            .fetch(this::mapAccount);
    }

    public Optional<StoredIntegrationAccount> findAccount(String accountId) {
        return dsl.selectFrom(INTEGRATION_ACCOUNT)
            .where(field(name("id"), String.class).eq(accountId))
            .fetchOptional(this::mapAccount);
    }

    public void saveAccount(StoredIntegrationAccount account) {
        dsl.insertInto(INTEGRATION_ACCOUNT)
            .set(field(name("id"), String.class), account.id())
            .set(field(name("connector_type"), String.class), account.connectorType().name())
            .set(field(name("name"), String.class), account.name())
            .set(field(name("status"), String.class), account.status().name())
            .set(field(name("config"), JSONB.class), jsonbSupport.toJsonb(account.config()))
            .set(field(name("credential_ciphertext"), String.class), account.credentialCiphertext())
            .set(field(name("credential_fingerprint"), String.class), account.credentialFingerprint())
            .set(field(name("created_at"), java.time.OffsetDateTime.class), JooqTimeSupport.toOffsetDateTime(account.createdAt()))
            .set(field(name("updated_at"), java.time.OffsetDateTime.class), JooqTimeSupport.toOffsetDateTime(account.updatedAt()))
            .onDuplicateKeyUpdate()
            .set(field(name("connector_type"), String.class), account.connectorType().name())
            .set(field(name("name"), String.class), account.name())
            .set(field(name("status"), String.class), account.status().name())
            .set(field(name("config"), JSONB.class), jsonbSupport.toJsonb(account.config()))
            .set(field(name("credential_ciphertext"), String.class), account.credentialCiphertext())
            .set(field(name("credential_fingerprint"), String.class), account.credentialFingerprint())
            .set(field(name("updated_at"), java.time.OffsetDateTime.class), JooqTimeSupport.toOffsetDateTime(account.updatedAt()))
            .execute();
    }

    private StoredIntegrationAccount mapAccount(Record record) {
        return new StoredIntegrationAccount(
            record.get(field(name("id"), String.class)),
            ToolConnectorType.valueOf(record.get(field(name("connector_type"), String.class))),
            record.get(field(name("name"), String.class)),
            IntegrationAccountStatus.valueOf(record.get(field(name("status"), String.class))),
            jsonbSupport.read(record.get(field(name("config"), JSONB.class)), OBJECT_MAP),
            record.get(field(name("credential_ciphertext"), String.class)),
            record.get(field(name("credential_fingerprint"), String.class)),
            JooqTimeSupport.toInstant(record.get(field(name("created_at"), java.time.OffsetDateTime.class))),
            JooqTimeSupport.toInstant(record.get(field(name("updated_at"), java.time.OffsetDateTime.class)))
        );
    }
}
