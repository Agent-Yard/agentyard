alter table channel_outbound_delivery
    add column idempotency_key varchar(128) not null;

create unique index uk_channel_outbound_delivery_idempotency_key
    on channel_outbound_delivery (idempotency_key);
