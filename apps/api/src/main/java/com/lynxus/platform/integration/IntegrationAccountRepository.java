package com.lynxus.platform.integration;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.lynxus.persistence.jooqsupport.JooqJsonbSupport;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.shared.ConflictException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.exception.DataAccessException;
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

    public void acquireCredentialLifecycleLock(String accountId) {
        try {
            dsl.select(field(name("id"), String.class))
                .from(INTEGRATION_ACCOUNT)
                .where(field(name("id"), String.class).eq(accountId))
                .forUpdate()
                .noWait()
                .fetchOptional();
        } catch (DataAccessException error) {
            if (isLockUnavailable(error)) {
                throw new ConflictException("integration account credential lifecycle operation is already running");
            }
            throw error;
        }
    }

    public void saveAccount(StoredIntegrationAccount account) {
        dsl.insertInto(INTEGRATION_ACCOUNT)
            .set(field(name("id"), String.class), account.id())
            .set(field(name("subject_type"), String.class), account.subjectType().name())
            .set(field(name("subject_id"), String.class), account.subjectId())
            .set(field(name("name"), String.class), account.name())
            .set(field(name("status"), String.class), account.status().name())
            .set(field(name("config"), JSONB.class), jsonbSupport.toJsonb(account.config()))
            .set(field(name("external_secret_ref"), String.class), account.externalSecretRef())
            .set(field(name("credential_ciphertext"), String.class), account.credentialCiphertext())
            .set(field(name("credential_fingerprint"), String.class), account.credentialFingerprint())
            .set(field(name("credential_status"), String.class), account.credentialStatus().name())
            .set(field(name("metadata"), JSONB.class), jsonbSupport.toJsonb(account.metadata()))
            .set(field(name("created_at"), java.time.OffsetDateTime.class), JooqTimeSupport.toOffsetDateTime(account.createdAt()))
            .set(field(name("updated_at"), java.time.OffsetDateTime.class), JooqTimeSupport.toOffsetDateTime(account.updatedAt()))
            .onConflict(field(name("id"), String.class))
            .doUpdate()
            .set(field(name("subject_type"), String.class), account.subjectType().name())
            .set(field(name("subject_id"), String.class), account.subjectId())
            .set(field(name("name"), String.class), account.name())
            .set(field(name("status"), String.class), account.status().name())
            .set(field(name("config"), JSONB.class), jsonbSupport.toJsonb(account.config()))
            .set(field(name("external_secret_ref"), String.class), account.externalSecretRef())
            .set(field(name("credential_ciphertext"), String.class), account.credentialCiphertext())
            .set(field(name("credential_fingerprint"), String.class), account.credentialFingerprint())
            .set(field(name("credential_status"), String.class), account.credentialStatus().name())
            .set(field(name("metadata"), JSONB.class), jsonbSupport.toJsonb(account.metadata()))
            .set(field(name("updated_at"), java.time.OffsetDateTime.class), JooqTimeSupport.toOffsetDateTime(account.updatedAt()))
            .execute();
    }

    private StoredIntegrationAccount mapAccount(Record record) {
        return new StoredIntegrationAccount(
            record.get(field(name("id"), String.class)),
            IntegrationAccountSubjectType.valueOf(record.get(field(name("subject_type"), String.class))),
            record.get(field(name("subject_id"), String.class)),
            record.get(field(name("name"), String.class)),
            IntegrationAccountStatus.valueOf(record.get(field(name("status"), String.class))),
            jsonbSupport.read(record.get(field(name("config"), JSONB.class)), OBJECT_MAP),
            record.get(field(name("external_secret_ref"), String.class)),
            record.get(field(name("credential_ciphertext"), String.class)),
            record.get(field(name("credential_fingerprint"), String.class)),
            IntegrationAccountCredentialStatus.valueOf(record.get(field(name("credential_status"), String.class))),
            jsonbSupport.read(record.get(field(name("metadata"), JSONB.class)), OBJECT_MAP),
            JooqTimeSupport.toInstant(record.get(field(name("created_at"), java.time.OffsetDateTime.class))),
            JooqTimeSupport.toInstant(record.get(field(name("updated_at"), java.time.OffsetDateTime.class)))
        );
    }

    private static boolean isLockUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
