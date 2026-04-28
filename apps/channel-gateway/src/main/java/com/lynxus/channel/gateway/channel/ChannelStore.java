package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.jooqsupport.JooqJsonbSupport;
import com.lynxus.channel.gateway.jooqsupport.JooqTimeSupport;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAccount;
import com.lynxus.contracts.channel.ChannelContracts.ChannelAccountStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBindingStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEventStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDeliveryStatus;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;

import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_ACCOUNT;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_CONVERSATION_BINDING;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_INBOUND_EVENT;
import static com.lynxus.channel.gateway.jooq.Tables.CHANNEL_OUTBOUND_DELIVERY;

final class ChannelStore {
    private final DSLContext dsl;
    private final JooqJsonbSupport jsonbSupport;

    ChannelStore(DSLContext dsl, JooqJsonbSupport jsonbSupport) {
        this.dsl = dsl;
        this.jsonbSupport = jsonbSupport;
    }

    List<ChannelAccount> listAccounts() {
        return dsl.selectFrom(CHANNEL_ACCOUNT)
            .orderBy(CHANNEL_ACCOUNT.UPDATED_AT.desc(), CHANNEL_ACCOUNT.ID.asc())
            .fetch(this::mapAccount);
    }

    Optional<ChannelAccount> findAccount(String accountId) {
        return dsl.selectFrom(CHANNEL_ACCOUNT)
            .where(CHANNEL_ACCOUNT.ID.eq(accountId))
            .fetchOptional(this::mapAccount);
    }

    List<ChannelAccount> listAccountsByProvider(ChannelProviderType providerType) {
        return dsl.selectFrom(CHANNEL_ACCOUNT)
            .where(CHANNEL_ACCOUNT.PROVIDER_TYPE.eq(providerType.name()))
            .orderBy(CHANNEL_ACCOUNT.UPDATED_AT.desc(), CHANNEL_ACCOUNT.ID.asc())
            .fetch(this::mapAccount);
    }

    void saveAccount(ChannelAccount account) {
        dsl.insertInto(CHANNEL_ACCOUNT)
            .set(CHANNEL_ACCOUNT.ID, account.id())
            .set(CHANNEL_ACCOUNT.PROVIDER_TYPE, account.providerType().name())
            .set(CHANNEL_ACCOUNT.NAME, account.name())
            .set(CHANNEL_ACCOUNT.STATUS, account.status().name())
            .set(CHANNEL_ACCOUNT.CONFIG, jsonbSupport.toJsonb(account.config() == null ? Map.of() : account.config()))
            .set(CHANNEL_ACCOUNT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(account.createdAt()))
            .set(CHANNEL_ACCOUNT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(account.updatedAt()))
            .onConflict(CHANNEL_ACCOUNT.ID)
            .doUpdate()
            .set(CHANNEL_ACCOUNT.PROVIDER_TYPE, account.providerType().name())
            .set(CHANNEL_ACCOUNT.NAME, account.name())
            .set(CHANNEL_ACCOUNT.STATUS, account.status().name())
            .set(CHANNEL_ACCOUNT.CONFIG, jsonbSupport.toJsonb(account.config() == null ? Map.of() : account.config()))
            .set(CHANNEL_ACCOUNT.CREATED_AT, JooqTimeSupport.toOffsetDateTime(account.createdAt()))
            .set(CHANNEL_ACCOUNT.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(account.updatedAt()))
            .execute();
    }

    List<ChannelConversationBinding> listBindings(String accountId) {
        return dsl.selectFrom(CHANNEL_CONVERSATION_BINDING)
            .where(CHANNEL_CONVERSATION_BINDING.CHANNEL_ACCOUNT_ID.eq(accountId))
            .orderBy(CHANNEL_CONVERSATION_BINDING.UPDATED_AT.desc(), CHANNEL_CONVERSATION_BINDING.ID.asc())
            .fetch(this::mapBinding);
    }

    void saveBinding(ChannelConversationBinding binding) {
        dsl.insertInto(CHANNEL_CONVERSATION_BINDING)
            .set(CHANNEL_CONVERSATION_BINDING.ID, binding.id())
            .set(CHANNEL_CONVERSATION_BINDING.CHANNEL_ACCOUNT_ID, binding.channelAccountId())
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
            .set(CHANNEL_CONVERSATION_BINDING.CHANNEL_ACCOUNT_ID, binding.channelAccountId())
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

    List<ChannelInboundEvent> listInboundEvents(String accountId) {
        return dsl.selectFrom(CHANNEL_INBOUND_EVENT)
            .where(CHANNEL_INBOUND_EVENT.CHANNEL_ACCOUNT_ID.eq(accountId))
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
            .set(CHANNEL_INBOUND_EVENT.CHANNEL_ACCOUNT_ID, event.channelAccountId())
            .set(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE, event.providerType().name())
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
            .set(CHANNEL_INBOUND_EVENT.CHANNEL_ACCOUNT_ID, event.channelAccountId())
            .set(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE, event.providerType().name())
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

    List<ChannelOutboundDelivery> listOutboundDeliveries(String accountId) {
        return dsl.selectFrom(CHANNEL_OUTBOUND_DELIVERY)
            .where(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_ACCOUNT_ID.eq(accountId))
            .orderBy(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT.desc(), CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID.asc())
            .fetch(this::mapOutboundDelivery);
    }

    void saveOutboundDelivery(ChannelOutboundDelivery delivery) {
        dsl.insertInto(CHANNEL_OUTBOUND_DELIVERY)
            .set(CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID, delivery.deliveryId())
            .set(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_ACCOUNT_ID, delivery.channelAccountId())
            .set(CHANNEL_OUTBOUND_DELIVERY.PROVIDER_TYPE, delivery.providerType().name())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_ID, delivery.sessionId())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_MESSAGE_ID, delivery.sessionMessageId())
            .set(CHANNEL_OUTBOUND_DELIVERY.EXTERNAL_CONVERSATION_ID, delivery.externalConversationId())
            .set(CHANNEL_OUTBOUND_DELIVERY.PAYLOAD, jsonbSupport.toJsonb(delivery.payload() == null ? Map.of() : delivery.payload()))
            .set(CHANNEL_OUTBOUND_DELIVERY.STATUS, delivery.status().name())
            .set(CHANNEL_OUTBOUND_DELIVERY.ATTEMPT_COUNT, delivery.attemptCount())
            .set(CHANNEL_OUTBOUND_DELIVERY.LAST_ERROR, delivery.lastError())
            .set(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.createdAt()))
            .set(CHANNEL_OUTBOUND_DELIVERY.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.updatedAt()))
            .onConflict(CHANNEL_OUTBOUND_DELIVERY.DELIVERY_ID)
            .doUpdate()
            .set(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_ACCOUNT_ID, delivery.channelAccountId())
            .set(CHANNEL_OUTBOUND_DELIVERY.PROVIDER_TYPE, delivery.providerType().name())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_ID, delivery.sessionId())
            .set(CHANNEL_OUTBOUND_DELIVERY.SESSION_MESSAGE_ID, delivery.sessionMessageId())
            .set(CHANNEL_OUTBOUND_DELIVERY.EXTERNAL_CONVERSATION_ID, delivery.externalConversationId())
            .set(CHANNEL_OUTBOUND_DELIVERY.PAYLOAD, jsonbSupport.toJsonb(delivery.payload() == null ? Map.of() : delivery.payload()))
            .set(CHANNEL_OUTBOUND_DELIVERY.STATUS, delivery.status().name())
            .set(CHANNEL_OUTBOUND_DELIVERY.ATTEMPT_COUNT, delivery.attemptCount())
            .set(CHANNEL_OUTBOUND_DELIVERY.LAST_ERROR, delivery.lastError())
            .set(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.createdAt()))
            .set(CHANNEL_OUTBOUND_DELIVERY.UPDATED_AT, JooqTimeSupport.toOffsetDateTime(delivery.updatedAt()))
            .execute();
    }

    private ChannelAccount mapAccount(Record record) {
        return new ChannelAccount(
            record.get(CHANNEL_ACCOUNT.ID),
            ChannelProviderType.valueOf(record.get(CHANNEL_ACCOUNT.PROVIDER_TYPE)),
            record.get(CHANNEL_ACCOUNT.NAME),
            ChannelAccountStatus.valueOf(record.get(CHANNEL_ACCOUNT.STATUS)),
            jsonbSupport.readObjectMap(record.get(CHANNEL_ACCOUNT.CONFIG)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_ACCOUNT.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_ACCOUNT.UPDATED_AT))
        );
    }

    private ChannelConversationBinding mapBinding(Record record) {
        return new ChannelConversationBinding(
            record.get(CHANNEL_CONVERSATION_BINDING.ID),
            record.get(CHANNEL_CONVERSATION_BINDING.CHANNEL_ACCOUNT_ID),
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
            record.get(CHANNEL_INBOUND_EVENT.CHANNEL_ACCOUNT_ID),
            ChannelProviderType.valueOf(record.get(CHANNEL_INBOUND_EVENT.PROVIDER_TYPE)),
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
            record.get(CHANNEL_OUTBOUND_DELIVERY.CHANNEL_ACCOUNT_ID),
            ChannelProviderType.valueOf(record.get(CHANNEL_OUTBOUND_DELIVERY.PROVIDER_TYPE)),
            record.get(CHANNEL_OUTBOUND_DELIVERY.SESSION_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.SESSION_MESSAGE_ID),
            record.get(CHANNEL_OUTBOUND_DELIVERY.EXTERNAL_CONVERSATION_ID),
            jsonbSupport.readObjectMap(record.get(CHANNEL_OUTBOUND_DELIVERY.PAYLOAD)),
            ChannelOutboundDeliveryStatus.valueOf(record.get(CHANNEL_OUTBOUND_DELIVERY.STATUS)),
            record.get(CHANNEL_OUTBOUND_DELIVERY.ATTEMPT_COUNT),
            record.get(CHANNEL_OUTBOUND_DELIVERY.LAST_ERROR),
            JooqTimeSupport.toInstant(record.get(CHANNEL_OUTBOUND_DELIVERY.CREATED_AT)),
            JooqTimeSupport.toInstant(record.get(CHANNEL_OUTBOUND_DELIVERY.UPDATED_AT))
        );
    }
}
