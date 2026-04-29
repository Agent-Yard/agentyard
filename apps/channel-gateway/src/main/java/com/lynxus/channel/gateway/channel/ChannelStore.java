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
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
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
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_CONVERSATION_BINDING;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_EVENT;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_OUTBOUND_DELIVERY;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_JOB_RUN;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_PROFILE_TEMPLATE_BINDING;

final class ChannelStore {
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

    Optional<ChannelOutboundProfileSnapshot> findOutboundProfileSnapshot(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_PROFILE)
            .where(CHANNEL_PROFILE.ID.eq(channelProfileId))
            .fetchOptional(record -> new ChannelOutboundProfileSnapshot(
                record.get(CHANNEL_PROFILE.ID),
                record.get(CHANNEL_PROFILE.PROVIDER_TYPE),
                ChannelProfileStatus.valueOf(record.get(CHANNEL_PROFILE.STATUS)),
                jsonbSupport.readObjectMap(record.get(CHANNEL_PROFILE.CONFIG)),
                readAssistantBinding(record),
                record.get(CHANNEL_PROFILE.EXTERNAL_SECRET_REF)
            ));
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

    Optional<ChannelConversationBinding> findBindingByProfileAndExternalConversation(
        String channelProfileId,
        String externalConversationId
    ) {
        return dsl.selectFrom(CHANNEL_CONVERSATION_BINDING)
            .where(CHANNEL_CONVERSATION_BINDING.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .and(CHANNEL_CONVERSATION_BINDING.EXTERNAL_CONVERSATION_ID.eq(externalConversationId))
            .fetchOptional(this::mapBinding);
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

    List<ChannelOutboundDelivery> listOutboundDeliveries(String channelProfileId) {
        return dsl.selectFrom(CHANNEL_OUTBOUND_DELIVERY)
            .where(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_PROFILE_ID.eq(channelProfileId))
            .orderBy(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT.desc(), CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID.asc())
            .fetch(this::mapOutboundDelivery);
    }

    void saveOutboundDelivery(ChannelOutboundDelivery delivery) {
        dsl.insertInto(CHANNEL_OUTBOUND_DELIVERY)
            .set(CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID, delivery.deliveryId())
            .set(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_PROFILE_ID, delivery.channelProfileId())
            .set(CHANNEL_OUTBOUND_DELIVERY.PROVIDER_TYPE, delivery.providerType())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_ID, delivery.sessionId())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_MESSAGE_ID, delivery.sessionMessageId())
            .set(CHANNEL_OUTBOUND_DELIVERY.EXTERNAL_CONVERSATION_ID, delivery.externalConversationId())
            .set(CHANNEL_OUTBOUND_DELIVERY.IDEMPOTENCY_KEY, delivery.idempotencyKey())
            .set(CHANNEL_OUTBOUND_DELIVERY.PAYLOAD, jsonbSupport.toJsonb(delivery.payload() == null ? Map.of() : delivery.payload()))
            .set(CHANNEL_OUTBOUND_DELIVERY.STATUS, delivery.status().name())
            .set(CHANNEL_OUTBOUND_DELIVERY.ATTEMPT_COUNT, delivery.attemptCount())
            .set(CHANNEL_OUTBOUND_DELIVERY.LAST_ERROR, delivery.lastError())
            .set(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.createdAt()))
            .set(CHANNEL_OUTBOUND_DELIVERY.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.updatedAt()))
            .onConflict(CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID)
            .doUpdate()
            .set(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_PROFILE_ID, delivery.channelProfileId())
            .set(CHANNEL_OUTBOUND_DELIVERY.PROVIDER_TYPE, delivery.providerType())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_ID, delivery.sessionId())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_MESSAGE_ID, delivery.sessionMessageId())
            .set(CHANNEL_OUTBOUND_DELIVERY.EXTERNAL_CONVERSATION_ID, delivery.externalConversationId())
            .set(CHANNEL_OUTBOUND_DELIVERY.IDEMPOTENCY_KEY, delivery.idempotencyKey())
            .set(CHANNEL_OUTBOUND_DELIVERY.PAYLOAD, jsonbSupport.toJsonb(delivery.payload() == null ? Map.of() : delivery.payload()))
            .set(CHANNEL_OUTBOUND_DELIVERY.STATUS, delivery.status().name())
            .set(CHANNEL_OUTBOUND_DELIVERY.ATTEMPT_COUNT, delivery.attemptCount())
            .set(CHANNEL_OUTBOUND_DELIVERY.LAST_ERROR, delivery.lastError())
            .set(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.createdAt()))
            .set(CHANNEL_OUTBOUND_DELIVERY.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.updatedAt()))
            .execute();
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

    private ChannelOutboundDelivery mapOutboundDelivery(Record record) {
        return new ChannelOutboundDelivery(
            record.get(CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_PROFILE_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.PROVIDER_TYPE),
            record.get(CHANNEL_OUTBOUND_DELIVERY.SESSION_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.SESSION_MESSAGE_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.EXTERNAL_CONVERSATION_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.IDEMPOTENCY_KEY),
            jsonbSupport.readObjectMap(record.get(CHANNEL_OUTBOUND_DELIVERY.PAYLOAD)),
            ChannelOutboundDeliveryStatus.valueOf(record.get(CHANNEL_OUTBOUND_DELIVERY.STATUS)),
            record.get(CHANNEL_OUTBOUND_DELIVERY.ATTEMPT_COUNT),
            record.get(CHANNEL_OUTBOUND_DELIVERY.LAST_ERROR),
            JooqTimeSupport.toInstant(record.get(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_OUTBOUND_DELIVERY.UPDATED_AT))
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
