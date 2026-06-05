package com.agentyard.persistence.shared;

import org.jooq.DSLContext;

import static com.agentyard.persistence.jooq.Tables.SHARED_STATE_REVISION;

public final class SharedStateRevisionStore {
    private final DSLContext dsl;

    public SharedStateRevisionStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long revision(String domain) {
        Long revision = dsl.select(SHARED_STATE_REVISION.REVISION)
            .from(SHARED_STATE_REVISION)
            .where(SHARED_STATE_REVISION.DOMAIN.eq(domain))
            .fetchOne(SHARED_STATE_REVISION.REVISION);
        return revision == null ? 0L : revision;
    }

    public boolean reserveRevision(String domain, long expectedRevision) {
        return dsl.update(SHARED_STATE_REVISION)
            .set(SHARED_STATE_REVISION.REVISION, SHARED_STATE_REVISION.REVISION.add(1))
            .set(SHARED_STATE_REVISION.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
            .where(SHARED_STATE_REVISION.DOMAIN.eq(domain))
            .and(SHARED_STATE_REVISION.REVISION.eq(expectedRevision))
            .execute() == 1;
    }
}
