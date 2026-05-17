package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.jooqsupport.JooqJsonbSupport;
import com.lynxus.channel.gateway.jooqsupport.JooqTimeSupport;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAssistantBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundTurnMessageStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundTurnStatus;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelAttachment;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessageRole;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelMessageSender;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundBindingSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundConsumerKind;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundProfileConsumer;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRun;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRunStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobScheduleType;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.impl.DSL;
import tools.jackson.core.type.TypeReference;

import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_CONVERSATION_BINDING;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_EVENT;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_MESSAGE_DEDUPE;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_TURN;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_TURN_MESSAGE;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_OUTBOUND_FINAL_CHECKPOINT;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB_RUN;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_TEMPLATE_BINDING;

final class ChannelStore {
    private static final TypeReference<List<NormalizedChannelAttachment>> ATTACHMENT_LIST = new TypeReference<>() {
    };

    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;

    ChannelStore(DSLContext dsl, JooqJsonbSupport jsonbSupport) {
        this.dsl = dsl;
        this.jsonbSupport = jsonbSupport;
    }

    List<ChannelGatewayProfile> listProfiles() {
        return dsl.selectFrom(CHANNEL_PROFILE)
            .orderBy(CHANNEL_PROFILE.UPDATED_AT.desc(), CHANNEL_PROFILE.ID.asc())
            .fetch(this::mapProfile);
    }

    Optional<ChannelGatewayProfile> findProfile(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_PROFILE)
            .where(CHANNEL_PROFILE.ID.eq(channelProfileId))
            .fetchOptional(this::mapProfile);
    }

    List<ChannelGatewayProfile> listProfilesByProvider(String providerType) {
        return dsl.selectFrom(CHANNEL_PROFILE)
            .where(CHANNEL_PROFILE.PROVIDER_TYPE.eq(providerType))
            .orderBy(CHANNEL_PROFILE.UPDATED_AT.desc(), CHANNEL_PROFILE.ID.asc())
            .fetch(this::mapProfile);
    }

    void createProfile(ChannelGatewayProfile profile, String externalSecretRef) {
        dsl.insertInto(CHANNEL_PROFILE)
            .set(CHANNEL_PROFILE.ID, profile.id())
            .set(CHANNEL_PROFILE.PROVIDER_TYPE, profile.providerType())
            .set(CHANNEL_PROFILE.DISPLAY_NAME, profile.displayName())
            .set(CHANNEL_PROFILE.STATUS, profile.status().name())
            .set(CHANNEL_PROFILE.INBOUND_ENABLED, profile.inboundEnabled())
            .set(CHANNEL_PROFILE.CONFIG, jsonbSupport.toJsonb(profile.config() == null ? Map.of() : profile.config()))
            .set(CHANNEL_PROFILE.ASSISTANT_BINDING, jsonbSupport.toJsonb(profile.assistantBinding() == null ? Map.of() : profile.assistantBinding()))
            .set(CHANNEL_PROFILE.INTEGRATION_ACCOUNT_ID, profile.accountId())
            .set(CHANNEL_PROFILE.EXTERNAL_SECRET_REF, externalSecretRef)
            .set(CHANNEL_PROFILE.REVISION, profile.revision())
            .set(CHANNEL_PROFILE.CREATED_AT, JooqTimeSupport.toOffsetDateTime(profile.createdAt()))
            .set(CHANNEL_PROFILE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(profile.updatedAt()))
            .execute();
    }

    boolean updateProfile(ChannelGatewayProfile profile, long expectedRevision, String externalSecretRef) {
        int rows = dsl.update(CHANNEL_PROFILE)
            .set(CHANNEL_PROFILE.PROVIDER_TYPE, profile.providerType())
            .set(CHANNEL_PROFILE.DISPLAY_NAME, profile.displayName())
            .set(CHANNEL_PROFILE.STATUS, profile.status().name())
            .set(CHANNEL_PROFILE.INBOUND_ENABLED, profile.inboundEnabled())
            .set(CHANNEL_PROFILE.CONFIG, jsonbSupport.toJsonb(profile.config() == null ? Map.of() : profile.config()))
            .set(CHANNEL_PROFILE.ASSISTANT_BINDING, jsonbSupport.toJsonb(profile.assistantBinding() == null ? Map.of() : profile.assistantBinding()))
            .set(CHANNEL_PROFILE.INTEGRATION_ACCOUNT_ID, profile.accountId())
            .set(CHANNEL_PROFILE.EXTERNAL_SECRET_REF, externalSecretRef)
            .set(CHANNEL_PROFILE.REVISION, profile.revision())
            .set(CHANNEL_PROFILE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(profile.updatedAt()))
            .where(CHANNEL_PROFILE.ID.eq(profile.id()))
            .and(CHANNEL_PROFILE.REVISION.eq(expectedRevision))
            .execute();
        return rows == 1;
    }

    boolean disableProfile(String channelProfileId, long expectedRevision, long nextRevision, Instant updatedAt) {
        int rows = dsl.update(CHANNEL_PROFILE)
            .set(CHANNEL_PROFILE.STATUS, ChannelProfileStatus.INACTIVE.name())
            .set(CHANNEL_PROFILE.REVISION, nextRevision)
            .set(CHANNEL_PROFILE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(updatedAt))
            .where(CHANNEL_PROFILE.ID.eq(channelProfileId))
            .and(CHANNEL_PROFILE.REVISION.eq(expectedRevision))
            .execute();
        return rows == 1;
    }

    List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_CONVERSATION_BINDING)
            .where(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .orderBy(CHANNEL_CONVERSATION_BINDING.UPDATED_AT.desc(), CHANNEL_CONVERSATION_BINDING.ID.asc())
            .fetch(this::mapBinding);
    }

    ChannelBindingSnapshotPageData listBindingSnapshots(Instant updatedAfter, String cursor, int limit) {
        Field<java.time.OffsetDateTime> snapshotUpdatedAt = snapshotUpdatedAtField();
        Cursor parsedCursor = Cursor.parse(cursor);
        Condition condition = DSL.trueCondition();
        if (updatedAfter != null) {
            condition = condition.and(snapshotUpdatedAt.gt(JooqTimeSupport.toOffsetDateTime(updatedAfter)));
        }
        if (parsedCursor != null) {
            condition = condition.and(snapshotUpdatedAt.gt(JooqTimeSupport.toOffsetDateTime(parsedCursor.updatedAt()))
                .or(snapshotUpdatedAt.eq(JooqTimeSupport.toOffsetDateTime(parsedCursor.updatedAt()))
                    .and(CHANNEL_CONVERSATION_BINDING.ID.gt(parsedCursor.bindingId()))));
        }
        int pageSize = Math.max(1, Math.min(limit, 1000));
        List<ChannelOutboundBindingSnapshot> rows = dsl.select(
                CHANNEL_CONVERSATION_BINDING.ID,
                CHANNEL_CONVERSATION_BINDING.SESSION_ID,
                CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID,
                CHANNEL_PROFILE.PROVIDER_TYPE,
                CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID,
                CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID,
                CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID,
                CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID,
                CHANNEL_CONVERSATION_BINDING.STATUS,
                CHANNEL_PROFILE.STATUS,
                CHANNEL_PROFILE.REVISION,
                CHANNEL_CONVERSATION_BINDING.UPDATED_AT,
                CHANNEL_PROFILE.UPDATED_AT,
                snapshotUpdatedAt
            )
            .from(CHANNEL_CONVERSATION_BINDING)
            .join(CHANNEL_PROFILE)
            .on(CHANNEL_PROFILE.ID.eq(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID))
            .where(condition)
            .orderBy(snapshotUpdatedAt.asc(), CHANNEL_CONVERSATION_BINDING.ID.asc())
            .limit(pageSize + 1)
            .fetch(record -> mapBindingSnapshot(record, snapshotUpdatedAt));
        boolean hasMore = rows.size() > pageSize;
        List<ChannelOutboundBindingSnapshot> pageItems = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore && !pageItems.isEmpty()
            ? Cursor.from(pageItems.getLast().updatedAt(), pageItems.getLast().bindingId())
            : null;
        return new ChannelBindingSnapshotPageData(List.copyOf(pageItems), nextCursor);
    }

    List<ChannelOutboundBindingSnapshot> listBindingSnapshotsByProfile(String channelProfileId) {
        Field<java.time.OffsetDateTime> snapshotUpdatedAt = snapshotUpdatedAtField();
        return dsl.select(
                CHANNEL_CONVERSATION_BINDING.ID,
                CHANNEL_CONVERSATION_BINDING.SESSION_ID,
                CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID,
                CHANNEL_PROFILE.PROVIDER_TYPE,
                CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID,
                CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID,
                CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID,
                CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID,
                CHANNEL_CONVERSATION_BINDING.STATUS,
                CHANNEL_PROFILE.STATUS,
                CHANNEL_PROFILE.REVISION,
                CHANNEL_CONVERSATION_BINDING.UPDATED_AT,
                CHANNEL_PROFILE.UPDATED_AT,
                snapshotUpdatedAt
            )
            .from(CHANNEL_CONVERSATION_BINDING)
            .join(CHANNEL_PROFILE)
            .on(CHANNEL_PROFILE.ID.eq(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID))
            .where(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .orderBy(snapshotUpdatedAt.desc(), CHANNEL_CONVERSATION_BINDING.ID.asc())
            .fetch(record -> mapBindingSnapshot(record, snapshotUpdatedAt));
    }

    Optional<ChannelConversationBinding> findBindingByProfileAndExternalConversation(
        String channelProfileId,
        String externalConversationId
    ) {
        return dsl.selectFrom(CHANNEL_CONVERSATION_BINDING)
            .where(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID.eq(externalConversationId))
            .fetchOptional(this::mapBinding);
    }

    Optional<ChannelConversationBinding> findBindingBySessionId(String sessionId) {
        return dsl.selectFrom(CHANNEL_CONVERSATION_BINDING)
            .where(CHANNEL_CONVERSATION_BINDING.SESSION_ID.eq(sessionId))
            .orderBy(CHANNEL_CONVERSATION_BINDING.UPDATED_AT.desc(), CHANNEL_CONVERSATION_BINDING.ID.asc())
            .limit(1)
            .fetchOptional(this::mapBinding);
    }

    List<ChannelOutboundBindingSnapshot> listActiveBindingSnapshotsBySessionId(String sessionId) {
        Field<java.time.OffsetDateTime> snapshotUpdatedAt = snapshotUpdatedAtField();
        return dsl.select(
                CHANNEL_CONVERSATION_BINDING.ID,
                CHANNEL_CONVERSATION_BINDING.SESSION_ID,
                CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID,
                CHANNEL_PROFILE.PROVIDER_TYPE,
                CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID,
                CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID,
                CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID,
                CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID,
                CHANNEL_CONVERSATION_BINDING.STATUS,
                CHANNEL_PROFILE.STATUS,
                CHANNEL_PROFILE.REVISION,
                CHANNEL_CONVERSATION_BINDING.UPDATED_AT,
                CHANNEL_PROFILE.UPDATED_AT,
                snapshotUpdatedAt
            )
            .from(CHANNEL_CONVERSATION_BINDING)
            .join(CHANNEL_PROFILE)
            .on(CHANNEL_PROFILE.ID.eq(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID))
            .where(CHANNEL_CONVERSATION_BINDING.SESSION_ID.eq(sessionId))
            .and(CHANNEL_CONVERSATION_BINDING.STATUS.eq(ChannelConversationBindingStatus.ACTIVE.name()))
            .and(CHANNEL_PROFILE.STATUS.eq(ChannelProfileStatus.ACTIVE.name()))
            .orderBy(snapshotUpdatedAt.desc(), CHANNEL_CONVERSATION_BINDING.ID.asc())
            .limit(2)
            .fetch(record -> mapBindingSnapshot(record, snapshotUpdatedAt));
    }

    void saveBinding(ChannelConversationBinding binding) {
        dsl.insertInto(CHANNEL_CONVERSATION_BINDING)
            .set(CHANNEL_CONVERSATION_BINDING.ID, binding.id())
            .set(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID, binding.channelProfileId())
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID, binding.externalConversationId())
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID, binding.externalUserId())
            .set(CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID, binding.assistantId())
            .set(CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID, binding.customerId())
            .set(CHANNEL_CONVERSATION_BINDING.SESSION_ID, binding.sessionId())
            .set(CHANNEL_CONVERSATION_BINDING.STATUS, binding.status().name())
            .set(CHANNEL_CONVERSATION_BINDING.METADATA, jsonbSupport.toJsonb(binding.metadata() == null ? Map.of() : binding.metadata()))
            .set(CHANNEL_CONVERSATION_BINDING.CREATED_AT, JooqTimeSupport.toOffsetDateTime(binding.createdAt()))
            .set(CHANNEL_CONVERSATION_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(binding.updatedAt()))
            .onConflict(CHANNEL_CONVERSATION_BINDING.ID)
            .doUpdate()
            .set(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID, binding.channelProfileId())
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID, binding.externalConversationId())
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID, binding.externalUserId())
            .set(CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID, binding.assistantId())
            .set(CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID, binding.customerId())
            .set(CHANNEL_CONVERSATION_BINDING.SESSION_ID, binding.sessionId())
            .set(CHANNEL_CONVERSATION_BINDING.STATUS, binding.status().name())
            .set(CHANNEL_CONVERSATION_BINDING.METADATA, jsonbSupport.toJsonb(binding.metadata() == null ? Map.of() : binding.metadata()))
            .set(CHANNEL_CONVERSATION_BINDING.CREATED_AT, JooqTimeSupport.toOffsetDateTime(binding.createdAt()))
            .set(CHANNEL_CONVERSATION_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(binding.updatedAt()))
            .execute();
    }

    void saveBindingForConversation(ChannelConversationBinding binding) {
        dsl.insertInto(CHANNEL_CONVERSATION_BINDING)
            .set(CHANNEL_CONVERSATION_BINDING.ID, binding.id())
            .set(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID, binding.channelProfileId())
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID, binding.externalConversationId())
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID, binding.externalUserId())
            .set(CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID, binding.assistantId())
            .set(CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID, binding.customerId())
            .set(CHANNEL_CONVERSATION_BINDING.SESSION_ID, binding.sessionId())
            .set(CHANNEL_CONVERSATION_BINDING.STATUS, binding.status().name())
            .set(CHANNEL_CONVERSATION_BINDING.METADATA, jsonbSupport.toJsonb(binding.metadata() == null ? Map.of() : binding.metadata()))
            .set(CHANNEL_CONVERSATION_BINDING.CREATED_AT, JooqTimeSupport.toOffsetDateTime(binding.createdAt()))
            .set(CHANNEL_CONVERSATION_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(binding.updatedAt()))
            .onConflict(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID, CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID)
            .doUpdate()
            .set(CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID, binding.externalUserId())
            .set(CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID, binding.assistantId())
            .set(CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID, binding.customerId())
            .set(CHANNEL_CONVERSATION_BINDING.STATUS, binding.status().name())
            .set(CHANNEL_CONVERSATION_BINDING.METADATA, jsonbSupport.toJsonb(binding.metadata() == null ? Map.of() : binding.metadata()))
            .set(CHANNEL_CONVERSATION_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(binding.updatedAt()))
            .execute();
    }

    List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_INBOUND_EVENT)
            .where(CHANNEL_INBOUND_EVENT.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .orderBy(CHANNEL_INBOUND_EVENT.CREATED_AT.desc(), CHANNEL_INBOUND_EVENT.EVENT_ID.asc())
            .fetch(this::mapInboundEvent);
    }

    Optional<ChannelInboundEvent> findInboundEventByDedupKey(String dedupKey) {
        return dsl.selectFrom(CHANNEL_INBOUND_EVENT)
            .where(CHANNEL_INBOUND_EVENT.DEDUP_KEY.eq(dedupKey))
            .fetchOptional(this::mapInboundEvent);
    }

    void saveInboundEvent(ChannelInboundEvent event) {
        dsl.insertInto(CHANNEL_INBOUND_EVENT)
            .set(CHANNEL_INBOUND_EVENT.EVENT_ID, event.eventId())
            .set(CHANNEL_INBOUND_EVENT.CHANNEL_PROFILE_ID, event.channelProfileId())
            .set(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE, event.providerType())
            .set(CHANNEL_INBOUND_EVENT.EVENT_TYPE, event.eventType())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_EVENT_ID, event.externalEventId())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_CONVERSATION_ID, event.externalConversationId())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_MESSAGE_ID, event.externalMessageId())
            .set(CHANNEL_INBOUND_EVENT.DEDUP_KEY, event.dedupKey())
            .set(CHANNEL_INBOUND_EVENT.RAW_PAYLOAD, jsonbSupport.toJsonb(event.rawPayload() == null ? Map.of() : event.rawPayload()))
            .set(CHANNEL_INBOUND_EVENT.NORMALIZED_PAYLOAD, jsonbSupport.toJsonb(event.normalizedPayload() == null ? Map.of() : event.normalizedPayload()))
            .set(CHANNEL_INBOUND_EVENT.STATUS, event.status().name())
            .set(CHANNEL_INBOUND_EVENT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(event.createdAt()))
            .set(CHANNEL_INBOUND_EVENT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(event.updatedAt()))
            .onConflict(CHANNEL_INBOUND_EVENT.EVENT_ID)
            .doUpdate()
            .set(CHANNEL_INBOUND_EVENT.CHANNEL_PROFILE_ID, event.channelProfileId())
            .set(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE, event.providerType())
            .set(CHANNEL_INBOUND_EVENT.EVENT_TYPE, event.eventType())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_EVENT_ID, event.externalEventId())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_CONVERSATION_ID, event.externalConversationId())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_MESSAGE_ID, event.externalMessageId())
            .set(CHANNEL_INBOUND_EVENT.DEDUP_KEY, event.dedupKey())
            .set(CHANNEL_INBOUND_EVENT.RAW_PAYLOAD, jsonbSupport.toJsonb(event.rawPayload() == null ? Map.of() : event.rawPayload()))
            .set(CHANNEL_INBOUND_EVENT.NORMALIZED_PAYLOAD, jsonbSupport.toJsonb(event.normalizedPayload() == null ? Map.of() : event.normalizedPayload()))
            .set(CHANNEL_INBOUND_EVENT.STATUS, event.status().name())
            .set(CHANNEL_INBOUND_EVENT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(event.createdAt()))
            .set(CHANNEL_INBOUND_EVENT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(event.updatedAt()))
            .execute();
    }

    boolean saveInboundEventIfAbsent(ChannelInboundEvent event) {
        int rows = dsl.insertInto(CHANNEL_INBOUND_EVENT)
            .set(CHANNEL_INBOUND_EVENT.EVENT_ID, event.eventId())
            .set(CHANNEL_INBOUND_EVENT.CHANNEL_PROFILE_ID, event.channelProfileId())
            .set(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE, event.providerType())
            .set(CHANNEL_INBOUND_EVENT.EVENT_TYPE, event.eventType())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_EVENT_ID, event.externalEventId())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_CONVERSATION_ID, event.externalConversationId())
            .set(CHANNEL_INBOUND_EVENT.EXTERNAL_MESSAGE_ID, event.externalMessageId())
            .set(CHANNEL_INBOUND_EVENT.DEDUP_KEY, event.dedupKey())
            .set(CHANNEL_INBOUND_EVENT.RAW_PAYLOAD, jsonbSupport.toJsonb(event.rawPayload() == null ? Map.of() : event.rawPayload()))
            .set(CHANNEL_INBOUND_EVENT.NORMALIZED_PAYLOAD, jsonbSupport.toJsonb(event.normalizedPayload() == null ? Map.of() : event.normalizedPayload()))
            .set(CHANNEL_INBOUND_EVENT.STATUS, event.status().name())
            .set(CHANNEL_INBOUND_EVENT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(event.createdAt()))
            .set(CHANNEL_INBOUND_EVENT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(event.updatedAt()))
            .onConflict(CHANNEL_INBOUND_EVENT.DEDUP_KEY)
            .doNothing()
            .execute();
        return rows == 1;
    }

    Optional<ChannelInboundTurnAudit> findInboundTurnByDedupKey(String dedupKey) {
        return dsl.selectFrom(CHANNEL_INBOUND_TURN)
            .where(CHANNEL_INBOUND_TURN.DEDUP_KEY.eq(dedupKey))
            .fetchOptional(this::mapInboundTurn);
    }

    boolean saveInboundTurnIfAbsent(ChannelInboundTurnAudit turn) {
        int rows = dsl.insertInto(CHANNEL_INBOUND_TURN)
            .set(CHANNEL_INBOUND_TURN.TURN_ID, turn.turnId())
            .set(CHANNEL_INBOUND_TURN.CHANNEL_PROFILE_ID, turn.channelProfileId())
            .set(CHANNEL_INBOUND_TURN.PROVIDER_TYPE, turn.providerType())
            .set(CHANNEL_INBOUND_TURN.DEDUP_KEY, turn.dedupKey())
            .set(CHANNEL_INBOUND_TURN.EXTERNAL_CONVERSATION_ID, turn.externalConversationId())
            .set(CHANNEL_INBOUND_TURN.EXTERNAL_USER_ID, turn.externalUserId())
            .set(CHANNEL_INBOUND_TURN.NORMALIZED_PAYLOAD, jsonbSupport.toJsonb(turn.normalizedPayload() == null ? Map.of() : turn.normalizedPayload()))
            .set(CHANNEL_INBOUND_TURN.RAW_PAYLOAD, jsonbSupport.toJsonb(turn.rawPayload() == null ? Map.of() : turn.rawPayload()))
            .set(CHANNEL_INBOUND_TURN.TRACE_CONTEXT, jsonbSupport.toJsonb(turn.traceContext() == null ? Map.of() : turn.traceContext()))
            .set(CHANNEL_INBOUND_TURN.METADATA, jsonbSupport.toJsonb(turn.metadata() == null ? Map.of() : turn.metadata()))
            .set(CHANNEL_INBOUND_TURN.STATUS, turn.status().name())
            .set(CHANNEL_INBOUND_TURN.SESSION_ID, turn.sessionId())
            .set(CHANNEL_INBOUND_TURN.CREATED_AT, JooqTimeSupport.toOffsetDateTime(turn.createdAt()))
            .set(CHANNEL_INBOUND_TURN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(turn.updatedAt()))
            .onConflict(CHANNEL_INBOUND_TURN.DEDUP_KEY)
            .doNothing()
            .execute();
        return rows == 1;
    }

    boolean saveInboundTurnMessageIfAbsent(ChannelInboundTurnMessageAudit message) {
        int rows = dsl.insertInto(CHANNEL_INBOUND_TURN_MESSAGE)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID, message.turnId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.CHANNEL_PROFILE_ID, message.channelProfileId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_CONVERSATION_ID, message.externalConversationId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.REQUEST_INDEX, message.requestIndex())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_EVENT_ID, message.externalEventId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_MESSAGE_ID, message.externalMessageId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.OCCURRED_AT, JooqTimeSupport.toOffsetDateTime(message.occurredAt()))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.ROLE, message.role().name())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.SENDER, jsonbSupport.toJsonb(message.sender()))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.MESSAGE_TYPE, message.messageType())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.TEXT, message.text())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.ATTACHMENTS, jsonbSupport.toJsonb(message.attachments() == null ? List.of() : message.attachments()))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.METADATA, jsonbSupport.toJsonb(message.metadata() == null ? Map.of() : message.metadata()))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.STATUS, message.status().name())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.SESSION_MESSAGE_ID, message.sessionMessageId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.DUPLICATE_OF_TURN_ID, message.duplicateOfTurnId())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.CREATED_AT, JooqTimeSupport.toOffsetDateTime(message.createdAt()))
            .set(CHANNEL_INBOUND_TURN_MESSAGE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(message.updatedAt()))
            .onConflict(CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID, CHANNEL_INBOUND_TURN_MESSAGE.REQUEST_INDEX)
            .doNothing()
            .execute();
        return rows == 1;
    }

    List<ChannelInboundTurnMessageAudit> listInboundTurnMessages(String turnId) {
        return dsl.selectFrom(CHANNEL_INBOUND_TURN_MESSAGE)
            .where(CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID.eq(turnId))
            .orderBy(CHANNEL_INBOUND_TURN_MESSAGE.REQUEST_INDEX.asc())
            .fetch(this::mapInboundTurnMessage);
    }

    boolean claimInboundMessageDedupe(
        String channelProfileId,
        String externalConversationId,
        String externalMessageId,
        String firstTurnId,
        int firstRequestIndex,
        Instant now
    ) {
        int rows = dsl.insertInto(CHANNEL_INBOUND_MESSAGE_DEDUPE)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID, channelProfileId)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID, externalConversationId)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID, externalMessageId)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_TURN_ID, firstTurnId)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_REQUEST_INDEX, firstRequestIndex)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.CREATED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .onConflict(
                CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID,
                CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID,
                CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID
            )
            .doNothing()
            .execute();
        return rows == 1;
    }

    Optional<ChannelInboundMessageDedupeAudit> findInboundMessageDedupe(
        String channelProfileId,
        String externalConversationId,
        String externalMessageId
    ) {
        return dsl.selectFrom(CHANNEL_INBOUND_MESSAGE_DEDUPE)
            .where(CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID.eq(externalConversationId))
            .and(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID.eq(externalMessageId))
            .fetchOptional(this::mapInboundMessageDedupe);
    }

    void updateInboundTurnStatus(String turnId, ChannelInboundTurnStatus status, String sessionId, Instant now) {
        dsl.update(CHANNEL_INBOUND_TURN)
            .set(CHANNEL_INBOUND_TURN.STATUS, status.name())
            .set(CHANNEL_INBOUND_TURN.SESSION_ID, sessionId)
            .set(CHANNEL_INBOUND_TURN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .where(CHANNEL_INBOUND_TURN.TURN_ID.eq(turnId))
            .execute();
    }

    void updateInboundTurnMessageStatus(
        String turnId,
        int requestIndex,
        ChannelInboundTurnMessageStatus status,
        String sessionMessageId,
        String duplicateOfTurnId,
        Instant now
    ) {
        var update = dsl.update(CHANNEL_INBOUND_TURN_MESSAGE)
            .set(CHANNEL_INBOUND_TURN_MESSAGE.STATUS, status.name())
            .set(CHANNEL_INBOUND_TURN_MESSAGE.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now));
        if (sessionMessageId != null) {
            update.set(CHANNEL_INBOUND_TURN_MESSAGE.SESSION_MESSAGE_ID, sessionMessageId);
        }
        if (duplicateOfTurnId != null) {
            update.set(CHANNEL_INBOUND_TURN_MESSAGE.DUPLICATE_OF_TURN_ID, duplicateOfTurnId);
        }
        update.where(CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID.eq(turnId))
            .and(CHANNEL_INBOUND_TURN_MESSAGE.REQUEST_INDEX.eq(requestIndex))
            .execute();
    }

    void updateInboundMessageDedupeSession(
        String channelProfileId,
        String externalConversationId,
        String externalMessageId,
        String sessionId,
        String sessionMessageId
    ) {
        dsl.update(CHANNEL_INBOUND_MESSAGE_DEDUPE)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.SESSION_ID, sessionId)
            .set(CHANNEL_INBOUND_MESSAGE_DEDUPE.SESSION_MESSAGE_ID, sessionMessageId)
            .where(CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID.eq(externalConversationId))
            .and(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID.eq(externalMessageId))
            .execute();
    }

    List<ChannelOutboundFrameCheckpoint> listOutboundFinalCheckpoints(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_OUTBOUND_FINAL_CHECKPOINT)
            .where(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .orderBy(
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE.asc(),
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND.asc(),
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID.asc()
            )
            .fetch(this::mapOutboundFinalCheckpoint);
    }

    Optional<ChannelOutboundFrameCheckpoint> findOutboundFinalCheckpoint(ChannelOutboundProfileConsumer consumer) {
        return dsl.selectFrom(CHANNEL_OUTBOUND_FINAL_CHECKPOINT)
            .where(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID.eq(consumer.channelProfileId()))
            .and(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE.eq(consumer.providerType()))
            .and(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND.eq(consumer.consumerKind().name()))
            .and(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID.eq(consumer.consumerId()))
            .fetchOptional(this::mapOutboundFinalCheckpoint);
    }

    void ensureOutboundFinalCheckpoint(ChannelOutboundProfileConsumer consumer, Instant now) {
        dsl.insertInto(CHANNEL_OUTBOUND_FINAL_CHECKPOINT)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID, consumer.channelProfileId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE, consumer.providerType())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND, consumer.consumerKind().name())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID, consumer.consumerId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.REGISTRATION_ID, consumer.registrationId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .onConflict(
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID,
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE,
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND,
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID
            )
            .doUpdate()
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.REGISTRATION_ID, consumer.registrationId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now))
            .execute();
    }

    boolean advanceOutboundFinalCheckpoint(
        ChannelOutboundProfileConsumer consumer,
        long finalSequence,
        String frameId,
        String sessionId,
        String sessionMessageId,
        Instant ackedAt
    ) {
        int rows = dsl.insertInto(CHANNEL_OUTBOUND_FINAL_CHECKPOINT)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID, consumer.channelProfileId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE, consumer.providerType())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND, consumer.consumerKind().name())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID, consumer.consumerId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.REGISTRATION_ID, consumer.registrationId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_SEQUENCE, finalSequence)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_FRAME_ID, frameId)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_SESSION_ID, sessionId)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_SESSION_MESSAGE_ID, sessionMessageId)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_AT, JooqTimeSupport.toOffsetDateTime(ackedAt))
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(ackedAt))
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(ackedAt))
            .onConflict(
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID,
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE,
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND,
                CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID
            )
            .doUpdate()
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.REGISTRATION_ID, consumer.registrationId())
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_SEQUENCE, finalSequence)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_FRAME_ID, frameId)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_SESSION_ID, sessionId)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_SESSION_MESSAGE_ID, sessionMessageId)
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_AT, JooqTimeSupport.toOffsetDateTime(ackedAt))
            .set(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(ackedAt))
            .where(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_SEQUENCE.isNull()
                .or(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_SEQUENCE.lt(finalSequence)))
            .execute();
        return rows == 1;
    }

    List<ChannelTemplateBinding> listTemplateBindings(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_PROFILE_TEMPLATE_BINDING)
            .where(CHANNEL_PROFILE_TEMPLATE_BINDING.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .orderBy(
                CHANNEL_PROFILE_TEMPLATE_BINDING.UPDATED_AT.desc(),
                CHANNEL_PROFILE_TEMPLATE_BINDING.ASSISTANT_ID.asc(),
                CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_TYPE.asc(),
                CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_SUBTYPE.asc(),
                CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_VERSION.asc()
            )
            .fetch(this::mapTemplateBinding);
    }

    Optional<ChannelTemplateBinding> findTemplateBinding(ChannelTemplateBindingKey key) {
        return dsl.selectFrom(CHANNEL_PROFILE_TEMPLATE_BINDING)
            .where(CHANNEL_PROFILE_TEMPLATE_BINDING.CHANNEL_PROFILE_ID.eq(key.channelProfileId()))
            .and(CHANNEL_PROFILE_TEMPLATE_BINDING.ASSISTANT_ID.eq(key.assistantId()))
            .and(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_TYPE.eq(key.messageType()))
            .and(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_SUBTYPE.eq(key.messageSubtype()))
            .and(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_VERSION.eq(key.messageVersion()))
            .fetchOptional(this::mapTemplateBinding);
    }

    void createTemplateBinding(ChannelTemplateBinding binding) {
        dsl.insertInto(CHANNEL_PROFILE_TEMPLATE_BINDING)
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.ID, binding.id())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.CHANNEL_PROFILE_ID, binding.channelProfileId())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.ASSISTANT_ID, binding.assistantId())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_TYPE, binding.messageType())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_SUBTYPE, binding.messageSubtype())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_VERSION, binding.messageVersion())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_TEMPLATE_ID, binding.externalTemplateId())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_TEMPLATE_VERSION, binding.externalTemplateVersion())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.ENABLED, binding.enabled())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.VARIABLE_SCHEMA, jsonbSupport.toJsonb(binding.variableSchema()))
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.DISPLAY_NAME, binding.displayName())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_EDIT_URL, binding.externalEditUrl())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.REVISION, binding.revision())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.CREATED_AT, JooqTimeSupport.toOffsetDateTime(binding.createdAt()))
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(binding.updatedAt()))
            .execute();
    }

    boolean updateTemplateBinding(ChannelTemplateBinding binding, long expectedRevision) {
        int rows = dsl.update(CHANNEL_PROFILE_TEMPLATE_BINDING)
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_TEMPLATE_ID, binding.externalTemplateId())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_TEMPLATE_VERSION, binding.externalTemplateVersion())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.ENABLED, binding.enabled())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.VARIABLE_SCHEMA, jsonbSupport.toJsonb(binding.variableSchema()))
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.DISPLAY_NAME, binding.displayName())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_EDIT_URL, binding.externalEditUrl())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.REVISION, binding.revision())
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(binding.updatedAt()))
            .where(CHANNEL_PROFILE_TEMPLATE_BINDING.ID.eq(binding.id()))
            .and(CHANNEL_PROFILE_TEMPLATE_BINDING.REVISION.eq(expectedRevision))
            .execute();
        return rows == 1;
    }

    boolean disableTemplateBinding(String bindingId, long expectedRevision, long nextRevision, Instant updatedAt) {
        int rows = dsl.update(CHANNEL_PROFILE_TEMPLATE_BINDING)
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.ENABLED, false)
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.REVISION, nextRevision)
            .set(CHANNEL_PROFILE_TEMPLATE_BINDING.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(updatedAt))
            .where(CHANNEL_PROFILE_TEMPLATE_BINDING.ID.eq(bindingId))
            .and(CHANNEL_PROFILE_TEMPLATE_BINDING.REVISION.eq(expectedRevision))
            .execute();
        return rows == 1;
    }

    List<ChannelProviderJobConfig> listJobs(String channelProfileId, List<String> jobTypes) {
        if (jobTypes == null || jobTypes.isEmpty()) {
            return List.of();
        }
        return dsl.selectFrom(CHANNEL_PROFILE_JOB)
            .where(CHANNEL_PROFILE_JOB.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(CHANNEL_PROFILE_JOB.JOB_TYPE.in(jobTypes))
            .orderBy(CHANNEL_PROFILE_JOB.JOB_TYPE.asc())
            .fetch(this::mapJob);
    }

    Optional<ChannelProviderJobConfig> findJob(String channelProfileId, String jobType) {
        return dsl.selectFrom(CHANNEL_PROFILE_JOB)
            .where(CHANNEL_PROFILE_JOB.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(CHANNEL_PROFILE_JOB.JOB_TYPE.eq(jobType))
            .fetchOptional(this::mapJob);
    }

    Optional<ChannelProviderJobConfig> findJobById(String jobId) {
        return dsl.selectFrom(CHANNEL_PROFILE_JOB)
            .where(CHANNEL_PROFILE_JOB.ID.eq(jobId))
            .fetchOptional(this::mapJob);
    }

    void createJob(String channelProfileId, ChannelProviderJobConfig job) {
        dsl.insertInto(CHANNEL_PROFILE_JOB)
            .set(CHANNEL_PROFILE_JOB.ID, job.jobId())
            .set(CHANNEL_PROFILE_JOB.CHANNEL_PROFILE_ID, channelProfileId)
            .set(CHANNEL_PROFILE_JOB.JOB_TYPE, job.jobType())
            .set(CHANNEL_PROFILE_JOB.STATUS, job.status().name())
            .set(CHANNEL_PROFILE_JOB.SCHEDULE_CONFIG, jsonbSupport.toJsonb(scheduleConfigJson(job.scheduleConfig())))
            .set(CHANNEL_PROFILE_JOB.NEXT_RUN_AT, JooqTimeSupport.toOffsetDateTime(job.nextRunAt()))
            .set(CHANNEL_PROFILE_JOB.LAST_RUN_AT, JooqTimeSupport.toOffsetDateTime(job.lastRunAt()))
            .set(CHANNEL_PROFILE_JOB.LAST_SUCCESS_AT, JooqTimeSupport.toOffsetDateTime(job.lastSuccessAt()))
            .set(CHANNEL_PROFILE_JOB.LAST_ERROR, job.lastError())
            .set(CHANNEL_PROFILE_JOB.FAILURE_COUNT, job.failureCount())
            .set(CHANNEL_PROFILE_JOB.REVISION, job.revision())
            .set(CHANNEL_PROFILE_JOB.CREATED_AT, JooqTimeSupport.toOffsetDateTime(job.createdAt()))
            .set(CHANNEL_PROFILE_JOB.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(job.updatedAt()))
            .execute();
    }

    boolean updateJob(ChannelProviderJobConfig job, long expectedRevision) {
        int rows = dsl.update(CHANNEL_PROFILE_JOB)
            .set(CHANNEL_PROFILE_JOB.STATUS, job.status().name())
            .set(CHANNEL_PROFILE_JOB.SCHEDULE_CONFIG, jsonbSupport.toJsonb(scheduleConfigJson(job.scheduleConfig())))
            .set(CHANNEL_PROFILE_JOB.NEXT_RUN_AT, JooqTimeSupport.toOffsetDateTime(job.nextRunAt()))
            .set(CHANNEL_PROFILE_JOB.REVISION, job.revision())
            .set(CHANNEL_PROFILE_JOB.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(job.updatedAt()))
            .where(CHANNEL_PROFILE_JOB.ID.eq(job.jobId()))
            .and(CHANNEL_PROFILE_JOB.REVISION.eq(expectedRevision))
            .and(CHANNEL_PROFILE_JOB.STATUS.ne(ChannelProviderJobStatus.RUNNING.name()))
            .execute();
        return rows == 1;
    }

    boolean disableJob(String jobId, long expectedRevision, long nextRevision, Instant updatedAt) {
        int rows = dsl.update(CHANNEL_PROFILE_JOB)
            .set(CHANNEL_PROFILE_JOB.STATUS, ChannelProviderJobStatus.DISABLED.name())
            .set(CHANNEL_PROFILE_JOB.NEXT_RUN_AT, JooqTimeSupport.toOffsetDateTime(null))
            .set(CHANNEL_PROFILE_JOB.REVISION, nextRevision)
            .set(CHANNEL_PROFILE_JOB.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(updatedAt))
            .where(CHANNEL_PROFILE_JOB.ID.eq(jobId))
            .and(CHANNEL_PROFILE_JOB.REVISION.eq(expectedRevision))
            .and(CHANNEL_PROFILE_JOB.STATUS.ne(ChannelProviderJobStatus.RUNNING.name()))
            .execute();
        return rows == 1;
    }

    List<ChannelProviderJobRun> listJobRuns(String jobId) {
        return dsl.selectFrom(CHANNEL_PROFILE_JOB_RUN)
            .where(CHANNEL_PROFILE_JOB_RUN.JOB_ID.eq(jobId))
            .orderBy(CHANNEL_PROFILE_JOB_RUN.SCHEDULED_AT.desc(), CHANNEL_PROFILE_JOB_RUN.ID.asc())
            .fetch(this::mapJobRun);
    }

    List<String> listDueActiveJobIds(Instant now, int limit) {
        return dsl.select(CHANNEL_PROFILE_JOB.ID)
            .from(CHANNEL_PROFILE_JOB)
            .where(CHANNEL_PROFILE_JOB.STATUS.eq(ChannelProviderJobStatus.ACTIVE.name()))
            .and(CHANNEL_PROFILE_JOB.NEXT_RUN_AT.le(JooqTimeSupport.toOffsetDateTime(now)))
            .and(scheduleTypeField().ne(ChannelProviderJobScheduleType.MANUAL.name()))
            .orderBy(CHANNEL_PROFILE_JOB.NEXT_RUN_AT.asc(), CHANNEL_PROFILE_JOB.ID.asc())
            .limit(Math.max(1, limit))
            .fetch(CHANNEL_PROFILE_JOB.ID);
    }

    Optional<ProviderJobClaim> claimJob(
        String jobId,
        String runId,
        String idempotencyKey,
        boolean manual,
        Instant now
    ) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record record = tx.select()
                .from(CHANNEL_PROFILE_JOB)
                .join(CHANNEL_PROFILE)
                .on(CHANNEL_PROFILE.ID.eq(CHANNEL_PROFILE_JOB.CHANNEL_PROFILE_ID))
                .where(CHANNEL_PROFILE_JOB.ID.eq(jobId))
                .forUpdate()
                .fetchOne();
            if (record == null || !ChannelProviderJobStatus.ACTIVE.name().equals(record.get(CHANNEL_PROFILE_JOB.STATUS))) {
                return Optional.empty();
            }
            ChannelProviderJobScheduleConfig scheduleConfig = readScheduleConfig(record);
            Instant nextRunAt = JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB.NEXT_RUN_AT));
            if (!manual) {
                if (scheduleConfig.scheduleType() == ChannelProviderJobScheduleType.MANUAL) {
                    return Optional.empty();
                }
                if (nextRunAt == null || nextRunAt.isAfter(now)) {
                    return Optional.empty();
                }
            }
            int timeoutSeconds = scheduleConfig.jobTimeoutSeconds();
            int attempt = record.get(CHANNEL_PROFILE_JOB.FAILURE_COUNT) + 1;
            tx.insertInto(CHANNEL_PROFILE_JOB_RUN)
                .set(CHANNEL_PROFILE_JOB_RUN.ID, runId)
                .set(CHANNEL_PROFILE_JOB_RUN.JOB_ID, jobId)
                .set(CHANNEL_PROFILE_JOB_RUN.STATUS, ChannelProviderJobRunStatus.RUNNING.name())
                .set(CHANNEL_PROFILE_JOB_RUN.SCHEDULED_AT, JooqTimeSupport.toOffsetDateTime(manual ? now : nextRunAt))
                .set(CHANNEL_PROFILE_JOB_RUN.STARTED_AT, JooqTimeSupport.toOffsetDateTime(now))
                .set(CHANNEL_PROFILE_JOB_RUN.JOB_TIMEOUT_SECONDS, timeoutSeconds)
                .set(CHANNEL_PROFILE_JOB_RUN.IDEMPOTENCY_KEY, idempotencyKey)
                .set(CHANNEL_PROFILE_JOB_RUN.ATTEMPT, attempt)
                .set(CHANNEL_PROFILE_JOB_RUN.EVENTS_INGESTED, 0)
                .set(CHANNEL_PROFILE_JOB_RUN.ERROR, jsonbSupport.toJsonb(Map.of()))
                .set(CHANNEL_PROFILE_JOB_RUN.METADATA, jsonbSupport.toJsonb(Map.of()))
                .set(CHANNEL_PROFILE_JOB_RUN.CREATED_AT, JooqTimeSupport.toOffsetDateTime(now))
                .set(CHANNEL_PROFILE_JOB_RUN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now))
                .execute();
            tx.update(CHANNEL_PROFILE_JOB)
                .set(CHANNEL_PROFILE_JOB.STATUS, ChannelProviderJobStatus.RUNNING.name())
                .set(CHANNEL_PROFILE_JOB.LAST_RUN_ID, runId)
                .set(CHANNEL_PROFILE_JOB.LAST_RUN_AT, JooqTimeSupport.toOffsetDateTime(now))
                .set(CHANNEL_PROFILE_JOB.REVISION, record.get(CHANNEL_PROFILE_JOB.REVISION) + 1)
                .set(CHANNEL_PROFILE_JOB.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now))
                .where(CHANNEL_PROFILE_JOB.ID.eq(jobId))
                .execute();
            return Optional.of(new ProviderJobClaim(
                jobId,
                runId,
                idempotencyKey,
                record.get(CHANNEL_PROFILE.ID),
                record.get(CHANNEL_PROFILE.PROVIDER_TYPE),
                jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE.CONFIG)),
                record.get(CHANNEL_PROFILE.EXTERNAL_SECRET_REF),
                record.get(CHANNEL_PROFILE_JOB.JOB_TYPE),
                scheduleConfig,
                record.get(CHANNEL_PROFILE_JOB.CURSOR),
                manual ? now : nextRunAt,
                now,
                timeoutSeconds
            ));
        });
    }

    boolean completeRunSucceeded(String jobId, String runId, ProviderJobExecutionResult result, Instant now) {
        return completeRun(jobId, runId, ChannelProviderJobRunStatus.SUCCEEDED, result, null, now, false);
    }

    boolean completeRunFailed(String jobId, String runId, String error, Instant now) {
        return completeRun(jobId, runId, ChannelProviderJobRunStatus.FAILED, ProviderJobExecutionResult.empty(), error, now, true);
    }

    boolean completeRunTimedOut(String jobId, String runId, String error, Instant now) {
        return completeRun(jobId, runId, ChannelProviderJobRunStatus.TIMED_OUT, ProviderJobExecutionResult.empty(), error, now, true);
    }

    List<ProviderJobRunningRun> listRunningRuns() {
        return dsl.select()
            .from(CHANNEL_PROFILE_JOB)
            .join(CHANNEL_PROFILE_JOB_RUN)
            .on(CHANNEL_PROFILE_JOB_RUN.ID.eq(CHANNEL_PROFILE_JOB.LAST_RUN_ID))
            .where(CHANNEL_PROFILE_JOB.STATUS.eq(ChannelProviderJobStatus.RUNNING.name()))
            .and(CHANNEL_PROFILE_JOB_RUN.STATUS.eq(ChannelProviderJobRunStatus.RUNNING.name()))
            .fetch(record -> new ProviderJobRunningRun(
                record.get(CHANNEL_PROFILE_JOB.ID),
                record.get(CHANNEL_PROFILE_JOB_RUN.ID),
                readScheduleConfig(record),
                JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.STARTED_AT)),
                record.get(CHANNEL_PROFILE_JOB_RUN.JOB_TIMEOUT_SECONDS)
            ));
    }

    boolean recoverTimedOutRun(String jobId, String runId, String error, Instant now) {
        return completeRun(jobId, runId, ChannelProviderJobRunStatus.TIMED_OUT, ProviderJobExecutionResult.empty(), error, now, true);
    }

    private boolean completeRun(
        String jobId,
        String runId,
        ChannelProviderJobRunStatus status,
        ProviderJobExecutionResult result,
        String error,
        Instant now,
        boolean incrementFailure
    ) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);
            Record record = tx.select()
                .from(CHANNEL_PROFILE_JOB)
                .join(CHANNEL_PROFILE_JOB_RUN)
                .on(CHANNEL_PROFILE_JOB_RUN.ID.eq(CHANNEL_PROFILE_JOB.LAST_RUN_ID))
                .where(CHANNEL_PROFILE_JOB.ID.eq(jobId))
                .and(CHANNEL_PROFILE_JOB.LAST_RUN_ID.eq(runId))
                .forUpdate()
                .fetchOne();
            if (record == null
                || !ChannelProviderJobStatus.RUNNING.name().equals(record.get(CHANNEL_PROFILE_JOB.STATUS))
                || !ChannelProviderJobRunStatus.RUNNING.name().equals(record.get(CHANNEL_PROFILE_JOB_RUN.STATUS))) {
                return false;
            }
            ChannelProviderJobScheduleConfig scheduleConfig = readScheduleConfig(record);
            Instant startedAt = JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.STARTED_AT));
            Instant nextRunAt = ChannelProviderJobScheduleCalculator.nextRunAt(scheduleConfig, now);
            tx.update(CHANNEL_PROFILE_JOB_RUN)
                .set(CHANNEL_PROFILE_JOB_RUN.STATUS, status.name())
                .set(CHANNEL_PROFILE_JOB_RUN.FINISHED_AT, JooqTimeSupport.toOffsetDateTime(now))
                .set(CHANNEL_PROFILE_JOB_RUN.DURATION_MS, Math.max(0L, java.time.Duration.between(startedAt, now).toMillis()))
                .set(CHANNEL_PROFILE_JOB_RUN.EVENTS_INGESTED, result.eventsIngested())
                .set(CHANNEL_PROFILE_JOB_RUN.NEXT_CURSOR, result.nextCursor())
                .set(CHANNEL_PROFILE_JOB_RUN.ERROR, jsonbSupport.toJsonb(error == null ? Map.of() : Map.of("message", error)))
                .set(CHANNEL_PROFILE_JOB_RUN.METADATA, jsonbSupport.toJsonb(result.metadata() == null ? Map.of() : result.metadata()))
                .set(CHANNEL_PROFILE_JOB_RUN.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now))
                .where(CHANNEL_PROFILE_JOB_RUN.ID.eq(runId))
                .and(CHANNEL_PROFILE_JOB_RUN.STATUS.eq(ChannelProviderJobRunStatus.RUNNING.name()))
                .execute();
            var update = tx.update(CHANNEL_PROFILE_JOB)
                .set(CHANNEL_PROFILE_JOB.STATUS, ChannelProviderJobStatus.ACTIVE.name())
                .set(CHANNEL_PROFILE_JOB.NEXT_RUN_AT, JooqTimeSupport.toOffsetDateTime(nextRunAt))
                .set(CHANNEL_PROFILE_JOB.REVISION, record.get(CHANNEL_PROFILE_JOB.REVISION) + 1)
                .set(CHANNEL_PROFILE_JOB.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(now));
            if (status == ChannelProviderJobRunStatus.SUCCEEDED) {
                update.set(CHANNEL_PROFILE_JOB.CURSOR, result.nextCursor())
                    .set(CHANNEL_PROFILE_JOB.LAST_SUCCESS_AT, JooqTimeSupport.toOffsetDateTime(now))
                    .set(CHANNEL_PROFILE_JOB.LAST_ERROR, (String) null)
                    .set(CHANNEL_PROFILE_JOB.LAST_ERROR_AT, JooqTimeSupport.toOffsetDateTime(null));
            }
            if (incrementFailure) {
                update.set(CHANNEL_PROFILE_JOB.FAILURE_COUNT, record.get(CHANNEL_PROFILE_JOB.FAILURE_COUNT) + 1)
                    .set(CHANNEL_PROFILE_JOB.LAST_ERROR, error)
                    .set(CHANNEL_PROFILE_JOB.LAST_ERROR_AT, JooqTimeSupport.toOffsetDateTime(now));
            }
            update.where(CHANNEL_PROFILE_JOB.ID.eq(jobId))
                .and(CHANNEL_PROFILE_JOB.STATUS.eq(ChannelProviderJobStatus.RUNNING.name()))
                .and(CHANNEL_PROFILE_JOB.LAST_RUN_ID.eq(runId))
                .execute();
            return true;
        });
    }

    private ChannelGatewayProfile mapProfile(Record record) {
        String externalSecretRef = record.get(CHANNEL_PROFILE.EXTERNAL_SECRET_REF);
        return new ChannelGatewayProfile(
            record.get(CHANNEL_PROFILE.ID),
            record.get(CHANNEL_PROFILE.PROVIDER_TYPE),
            record.get(CHANNEL_PROFILE.DISPLAY_NAME),
            ChannelProfileStatus.valueOf(record.get(CHANNEL_PROFILE.STATUS)),
            record.get(CHANNEL_PROFILE.INBOUND_ENABLED),
            jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE.CONFIG)),
            readAssistantBinding(record),
            record.get(CHANNEL_PROFILE.INTEGRATION_ACCOUNT_ID),
            externalSecretRef != null && !externalSecretRef.isBlank(),
            record.get(CHANNEL_PROFILE.REVISION),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE.UPDATED_AT))
        );
    }

    private static org.jooq.Field<String> scheduleTypeField() {
        return DSL.field("{0}->>'scheduleType'", String.class, CHANNEL_PROFILE_JOB.SCHEDULE_CONFIG);
    }

    private ChannelAssistantBinding readAssistantBinding(Record record) {
        Map<String, Object> raw = jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE.ASSISTANT_BINDING));
        if (raw.isEmpty()) {
            return null;
        }
        return new ChannelAssistantBinding(
            raw.get("assistantId") instanceof String assistantId ? assistantId : null,
            raw.get("scenarioId") instanceof String scenarioId ? scenarioId : null
        );
    }

    private ChannelConversationBinding mapBinding(Record record) {
        return new ChannelConversationBinding(
            record.get(CHANNEL_CONVERSATION_BINDING.ID),
            record.get(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.SESSION_ID),
            ChannelConversationBindingStatus.valueOf(record.get(CHANNEL_CONVERSATION_BINDING.STATUS)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_CONVERSATION_BINDING.METADATA)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_CONVERSATION_BINDING.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_CONVERSATION_BINDING.UPDATED_AT))
        );
    }

    private Field<java.time.OffsetDateTime> snapshotUpdatedAtField() {
        return DSL.greatest(CHANNEL_CONVERSATION_BINDING.UPDATED_AT, CHANNEL_PROFILE.UPDATED_AT).as("snapshot_updated_at");
    }

    private ChannelOutboundBindingSnapshot mapBindingSnapshot(Record record, Field<java.time.OffsetDateTime> snapshotUpdatedAt) {
        return new ChannelOutboundBindingSnapshot(
            record.get(CHANNEL_CONVERSATION_BINDING.ID),
            record.get(CHANNEL_CONVERSATION_BINDING.SESSION_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_PROFILE.PROVIDER_TYPE),
            record.get(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.EXTERNAL_USER_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.ASSISTANT_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.CUSTOMER_ID),
            record.get(CHANNEL_CONVERSATION_BINDING.STATUS),
            ChannelProfileStatus.valueOf(record.get(CHANNEL_PROFILE.STATUS)),
            record.get(CHANNEL_PROFILE.REVISION),
            JooqTimeSupport.toInstant(record.get(CHANNEL_CONVERSATION_BINDING.UPDATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE.UPDATED_AT)),
            JooqTimeSupport.toInstant(record.get(snapshotUpdatedAt))
        );
    }

    record ChannelBindingSnapshotPageData(List<ChannelOutboundBindingSnapshot> items, String nextCursor) {
    }

    private record Cursor(Instant updatedAt, String bindingId) {
        private static Cursor parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid binding snapshot cursor");
            }
            return new Cursor(Instant.parse(parts[0]), parts[1]);
        }

        private static String from(Instant updatedAt, String bindingId) {
            return updatedAt + "|" + bindingId;
        }
    }

    private ChannelInboundEvent mapInboundEvent(Record record) {
        return new ChannelInboundEvent(
            record.get(CHANNEL_INBOUND_EVENT.EVENT_ID),
            record.get(CHANNEL_INBOUND_EVENT.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE),
            record.get(CHANNEL_INBOUND_EVENT.EVENT_TYPE),
            record.get(CHANNEL_INBOUND_EVENT.EXTERNAL_EVENT_ID),
            record.get(CHANNEL_INBOUND_EVENT.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_INBOUND_EVENT.EXTERNAL_MESSAGE_ID),
            record.get(CHANNEL_INBOUND_EVENT.DEDUP_KEY),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_EVENT.RAW_PAYLOAD)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_EVENT.NORMALIZED_PAYLOAD)),
            ChannelInboundEventStatus.valueOf(record.get(CHANNEL_INBOUND_EVENT.STATUS)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_EVENT.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_EVENT.UPDATED_AT))
        );
    }

    private ChannelInboundTurnAudit mapInboundTurn(Record record) {
        return new ChannelInboundTurnAudit(
            record.get(CHANNEL_INBOUND_TURN.TURN_ID),
            record.get(CHANNEL_INBOUND_TURN.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_INBOUND_TURN.PROVIDER_TYPE),
            record.get(CHANNEL_INBOUND_TURN.DEDUP_KEY),
            record.get(CHANNEL_INBOUND_TURN.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_INBOUND_TURN.EXTERNAL_USER_ID),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_TURN.NORMALIZED_PAYLOAD)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_TURN.RAW_PAYLOAD)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_TURN.TRACE_CONTEXT)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_TURN.METADATA)),
            ChannelInboundTurnStatus.valueOf(record.get(CHANNEL_INBOUND_TURN.STATUS)),
            record.get(CHANNEL_INBOUND_TURN.SESSION_ID),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_TURN.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_TURN.UPDATED_AT))
        );
    }

    private ChannelInboundTurnMessageAudit mapInboundTurnMessage(Record record) {
        return new ChannelInboundTurnMessageAudit(
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.TURN_ID),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.REQUEST_INDEX),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_EVENT_ID),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.EXTERNAL_MESSAGE_ID),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_TURN_MESSAGE.OCCURRED_AT)),
            NormalizedChannelMessageRole.valueOf(record.get(CHANNEL_INBOUND_TURN_MESSAGE.ROLE)),
            jsonbSupport.read(record.get(CHANNEL_INBOUND_TURN_MESSAGE.SENDER), NormalizedChannelMessageSender.class),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.MESSAGE_TYPE),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.TEXT),
            readAttachmentList(record.get(CHANNEL_INBOUND_TURN_MESSAGE.ATTACHMENTS)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_INBOUND_TURN_MESSAGE.METADATA)),
            ChannelInboundTurnMessageStatus.valueOf(record.get(CHANNEL_INBOUND_TURN_MESSAGE.STATUS)),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.SESSION_MESSAGE_ID),
            record.get(CHANNEL_INBOUND_TURN_MESSAGE.DUPLICATE_OF_TURN_ID),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_TURN_MESSAGE.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_TURN_MESSAGE.UPDATED_AT))
        );
    }

    private ChannelInboundMessageDedupeAudit mapInboundMessageDedupe(Record record) {
        return new ChannelInboundMessageDedupeAudit(
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.EXTERNAL_MESSAGE_ID),
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_TURN_ID),
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.FIRST_REQUEST_INDEX),
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.SESSION_ID),
            record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.SESSION_MESSAGE_ID),
            JooqTimeSupport.toInstant(record.get(CHANNEL_INBOUND_MESSAGE_DEDUPE.CREATED_AT))
        );
    }

    private List<NormalizedChannelAttachment> readAttachmentList(JSONB value) {
        List<NormalizedChannelAttachment> attachments = jsonbSupport.read(value, ATTACHMENT_LIST);
        return attachments == null || attachments.isEmpty() ? List.of() : List.copyOf(attachments);
    }

    private ChannelOutboundFrameCheckpoint mapOutboundFinalCheckpoint(Record record) {
        return new ChannelOutboundFrameCheckpoint(
            new ChannelOutboundProfileConsumer(
                record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CHANNEL_PROFILE_ID),
                record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.PROVIDER_TYPE),
                ChannelOutboundConsumerKind.valueOf(record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_KIND)),
                record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.CONSUMER_ID),
                record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.REGISTRATION_ID)
            ),
            record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_SEQUENCE),
            record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_FINAL_FRAME_ID),
            record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_SESSION_ID),
            record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_SESSION_MESSAGE_ID),
            JooqTimeSupport.toInstant(record.get(CHANNEL_OUTBOUND_FINAL_CHECKPOINT.LAST_ACKED_AT))
        );
    }

    private ChannelTemplateBinding mapTemplateBinding(Record record) {
        return new ChannelTemplateBinding(
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.ID),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.ASSISTANT_ID),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_TYPE),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_SUBTYPE),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.MESSAGE_VERSION),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_TEMPLATE_ID),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_TEMPLATE_VERSION),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.ENABLED),
            jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.VARIABLE_SCHEMA)),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.DISPLAY_NAME),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.EXTERNAL_EDIT_URL),
            record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.REVISION),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_TEMPLATE_BINDING.UPDATED_AT))
        );
    }

    private ChannelProviderJobConfig mapJob(Record record) {
        return new ChannelProviderJobConfig(
            record.get(CHANNEL_PROFILE_JOB.ID),
            record.get(CHANNEL_PROFILE_JOB.JOB_TYPE),
            ChannelProviderJobStatus.valueOf(record.get(CHANNEL_PROFILE_JOB.STATUS)),
            readScheduleConfig(record),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB.NEXT_RUN_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB.LAST_RUN_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB.LAST_SUCCESS_AT)),
            record.get(CHANNEL_PROFILE_JOB.LAST_ERROR),
            record.get(CHANNEL_PROFILE_JOB.FAILURE_COUNT),
            record.get(CHANNEL_PROFILE_JOB.REVISION),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB.UPDATED_AT))
        );
    }

    private ChannelProviderJobRun mapJobRun(Record record) {
        String id = record.get(CHANNEL_PROFILE_JOB_RUN.ID);
        return new ChannelProviderJobRun(
            id,
            id,
            record.get(CHANNEL_PROFILE_JOB_RUN.JOB_ID),
            ChannelProviderJobRunStatus.valueOf(record.get(CHANNEL_PROFILE_JOB_RUN.STATUS)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.SCHEDULED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.STARTED_AT)),
            record.get(CHANNEL_PROFILE_JOB_RUN.JOB_TIMEOUT_SECONDS),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.FINISHED_AT)),
            record.get(CHANNEL_PROFILE_JOB_RUN.DURATION_MS),
            record.get(CHANNEL_PROFILE_JOB_RUN.IDEMPOTENCY_KEY),
            record.get(CHANNEL_PROFILE_JOB_RUN.ATTEMPT),
            record.get(CHANNEL_PROFILE_JOB_RUN.EVENTS_INGESTED),
            record.get(CHANNEL_PROFILE_JOB_RUN.NEXT_CURSOR),
            jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE_JOB_RUN.ERROR)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE_JOB_RUN.METADATA)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_PROFILE_JOB_RUN.UPDATED_AT))
        );
    }

    private ChannelProviderJobScheduleConfig readScheduleConfig(Record record) {
        Map<String, Object> raw = jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE_JOB.SCHEDULE_CONFIG));
        return new ChannelProviderJobScheduleConfig(
            ChannelProviderJobScheduleType.valueOf(String.valueOf(raw.get("scheduleType"))),
            integerValue(raw.get("intervalSeconds")),
            stringValue(raw.get("cronExpression")),
            stringValue(raw.get("timezone")),
            integerValue(raw.get("jobTimeoutSeconds")),
            raw.get("jobConfig") instanceof Map<?, ?> ? stringKeyMap((Map<?, ?>) raw.get("jobConfig")) : Map.of()
        );
    }

    private static Map<String, Object> scheduleConfigJson(ChannelProviderJobScheduleConfig scheduleConfig) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("scheduleType", scheduleConfig.scheduleType().name());
        result.put("intervalSeconds", scheduleConfig.intervalSeconds());
        result.put("cronExpression", scheduleConfig.cronExpression());
        result.put("timezone", scheduleConfig.timezone());
        result.put("jobTimeoutSeconds", scheduleConfig.jobTimeoutSeconds());
        result.put("jobConfig", scheduleConfig.jobConfig());
        return result;
    }

    private static Integer integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> raw) {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, value);
            }
        });
        return result;
    }
}
