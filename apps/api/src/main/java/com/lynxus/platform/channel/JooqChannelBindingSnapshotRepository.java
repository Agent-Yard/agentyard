package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.persistence.jooqsupport.JooqTimeSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Condition;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import static com.lynxus.persistence.jooq.Tables.CHANNEL_SESSION_BINDING_SNAPSHOT;

@Repository
public class JooqChannelBindingSnapshotRepository {
    private static final String ACTIVE = "ACTIVE";
    private static final String INACTIVE = "INACTIVE";

    private final DSLContext dsl;

    public JooqChannelBindingSnapshotRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void upsert(ChannelOutboundBindingSnapshot snapshot) {
        dsl.insertInto(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID, snapshot.bindingId())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID, blankToNull(snapshot.sessionId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID, snapshot.channelProfileId())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROVIDER_TYPE, snapshot.providerType())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID, blankToNull(snapshot.externalConversationId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_USER_ID, blankToNull(snapshot.externalUserId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID, blankToNull(snapshot.assistantId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.CUSTOMER_ID, blankToNull(snapshot.customerId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS, snapshot.bindingStatus())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_STATUS, snapshot.profileStatus().name())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_REVISION, snapshot.profileRevision())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(snapshot.bindingUpdatedAt()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(snapshot.profileUpdatedAt()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(snapshot.updatedAt()))
            .onConflict(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID)
            .doUpdate()
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID, blankToNull(snapshot.sessionId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID, snapshot.channelProfileId())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROVIDER_TYPE, snapshot.providerType())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID, blankToNull(snapshot.externalConversationId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_USER_ID, blankToNull(snapshot.externalUserId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID, blankToNull(snapshot.assistantId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.CUSTOMER_ID, blankToNull(snapshot.customerId()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS, snapshot.bindingStatus())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_STATUS, snapshot.profileStatus().name())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_REVISION, snapshot.profileRevision())
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(snapshot.bindingUpdatedAt()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_UPDATED_AT, JooqTimeSupport.toOffsetDateTime(snapshot.profileUpdatedAt()))
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(snapshot.updatedAt()))
            .execute();
    }

    public void upsertAll(List<ChannelOutboundBindingSnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        snapshots.forEach(this::upsert);
    }

    public Optional<ChannelOutboundBindingSnapshot> findActiveBySessionId(String sessionId) {
        List<ChannelOutboundBindingSnapshot> rows = dsl.selectFrom(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .where(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID.eq(requireText(sessionId, "sessionId")))
            .and(activeSnapshotCondition())
            .orderBy(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT.desc(), CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID.asc())
            .limit(2)
            .fetch(this::map);
        if (rows.size() > 1) {
            throw new ChannelBindingSnapshotIntegrityException("duplicate ACTIVE channel binding snapshots for session: " + sessionId);
        }
        return rows.stream().findFirst();
    }

    public List<ChannelOutboundBindingSnapshot> listActiveByProfileId(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .where(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID.eq(requireText(channelProfileId, "channelProfileId")))
            .and(activeSnapshotCondition())
            .orderBy(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT.desc(), CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID.asc())
            .fetch(this::map);
    }

    public void markInactive(String bindingId, Instant now) {
        dsl.update(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS, INACTIVE)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now == null ? Instant.now() : now))
            .where(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID.eq(requireText(bindingId, "bindingId")))
            .execute();
    }

    public int markActiveMissingFromFullRefreshInactive(Set<String> activeGatewayBindingIds, Instant now) {
        Condition condition = activeSnapshotCondition();
        if (activeGatewayBindingIds != null && !activeGatewayBindingIds.isEmpty()) {
            condition = condition.and(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID.notIn(activeGatewayBindingIds));
        }
        return dsl.update(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS, INACTIVE)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now == null ? Instant.now() : now))
            .where(condition)
            .execute();
    }

    public int markProfileActiveMissingInactive(String channelProfileId, Set<String> activeGatewayBindingIds, Instant now) {
        Condition condition = CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID.eq(requireText(channelProfileId, "channelProfileId"))
            .and(activeSnapshotCondition());
        if (activeGatewayBindingIds != null && !activeGatewayBindingIds.isEmpty()) {
            condition = condition.and(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID.notIn(activeGatewayBindingIds));
        }
        return dsl.update(CHANNEL_SESSION_BINDING_SNAPSHOT)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS, INACTIVE)
            .set(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now == null ? Instant.now() : now))
            .where(condition)
            .execute();
    }

    private static org.jooq.Condition activeSnapshotCondition() {
        return CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS.eq(ACTIVE)
            .and(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_STATUS.eq(ChannelProfileStatus.ACTIVE.name()))
            .and(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID.isNotNull());
    }

    private ChannelOutboundBindingSnapshot map(Record record) {
        return new ChannelOutboundBindingSnapshot(
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.SESSION_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.PROVIDER_TYPE),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.EXTERNAL_USER_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.ASSISTANT_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.CUSTOMER_ID),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_STATUS),
            ChannelProfileStatus.valueOf(record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_STATUS)),
            record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_REVISION),
            JooqTimeSupport.toInstant(record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.BINDING_UPDATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.PROFILE_UPDATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_SESSION_BINDING_SNAPSHOT.UPDATED_AT))
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
