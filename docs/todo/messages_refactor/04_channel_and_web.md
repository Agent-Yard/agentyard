# Messages Refactor Channel And Web

## 1. Channel Boundary

Message-class channel inbound uses `NormalizedChannelInboundTurn`.

Non-message channel events continue using `NormalizedChannelInboundEvent` and do not enter session-runtime message write path.

```ts
export type NormalizedChannelMessageRole =
  | 'USER'
  | 'ASSISTANT'
  | 'HUMAN_OPERATOR'
  | 'SYSTEM';

export type NormalizedChannelSenderType =
  | 'CUSTOMER'
  | 'AGENT'
  | 'HUMAN_OPERATOR'
  | 'SYSTEM';

export interface NormalizedChannelMessageSender {
  senderType: NormalizedChannelSenderType;
  senderId?: string | null;
  senderName?: string | null;
  metadata: Record<string, unknown>;
}

export interface NormalizedChannelInboundTurn {
  providerType: string;
  channelProfileId: string;
  dedupKey: string;
  externalConversationId: string;
  externalUserId?: string | null;
  conversation: NormalizedChannelConversation;
  sender?: NormalizedChannelMessageSender | null;
  messages: NormalizedChannelTurnMessage[];
  normalizedPayload: Record<string, unknown>;
  rawPayload?: Record<string, unknown> | null;
  traceContext: NormalizedChannelTraceContext;
  metadata?: Record<string, unknown> | null;
}

export interface NormalizedChannelTurnMessage {
  externalEventId?: string | null;
  externalMessageId: string;
  occurredAt?: string | null;
  role: NormalizedChannelMessageRole;
  sender?: NormalizedChannelMessageSender | null;
  type?: string | null;
  text?: string | null;
  attachments: NormalizedChannelAttachment[];
  metadata: Record<string, unknown>;
}
```

## 2. Gateway Endpoints

- `POST /internal/channel-turns/normalized` for message-class inbound turns
- `POST /internal/channel-events/normalized` for non-message events
- Pull-style provider job response supports both `inboundTurns[]` and `events[]`

Message-class inbound no longer writes `channel_inbound_event`.

## 3. Gateway Audit Tables

```sql
create table channel_inbound_turn (
    turn_id varchar(64) primary key,
    channel_profile_id varchar(64) not null,
    provider_type varchar(64) not null,
    dedup_key varchar(255) not null,
    external_conversation_id varchar(255) not null,
    external_user_id varchar(255),
    normalized_payload jsonb not null,
    raw_payload jsonb not null,
    trace_context jsonb not null,
    metadata jsonb not null,
    status varchar(32) not null,
    session_id varchar(64),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table channel_inbound_turn_message (
    turn_id varchar(64) not null,
    channel_profile_id varchar(64) not null,
    external_conversation_id varchar(255) not null,
    request_index integer not null,
    external_event_id varchar(255),
    external_message_id varchar(255) not null,
    occurred_at timestamp with time zone,
    role varchar(32) not null,
    sender jsonb not null,
    message_type varchar(64),
    text text,
    attachments jsonb not null,
    metadata jsonb not null,
    status varchar(32) not null,
    session_message_id varchar(64),
    duplicate_of_turn_id varchar(64),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (turn_id, request_index)
);

create table channel_inbound_message_dedupe (
    channel_profile_id varchar(64) not null,
    external_conversation_id varchar(255) not null,
    external_message_id varchar(255) not null,
    first_turn_id varchar(64) not null,
    first_request_index integer not null,
    session_id varchar(64),
    session_message_id varchar(64),
    created_at timestamp with time zone not null,
    primary key (channel_profile_id, external_conversation_id, external_message_id)
);

create unique index uk_channel_inbound_turn_dedup
    on channel_inbound_turn (dedup_key);
```

Statuses:

- Turn: `RECEIVED`, `DUPLICATE`, `DISPATCHED`, `PARTIALLY_DISPATCHED`, `REJECTED`, `FAILED`
- Message: `RECEIVED`, `DUPLICATE`, `ACCEPTED`, `REJECTED`

## 4. Channel Dispatch Rules

Message-class inbound turns use synchronous dispatch to session-runtime. The gateway endpoint should not acknowledge a non-duplicate message turn as accepted until session-runtime has accepted or rejected the internal `ChannelInboundSessionTurnRequest`.

Gateway processing order:

1. Validate headers and provider registration
2. Validate `NormalizedChannelInboundTurn`
3. Resolve channel profile and assistant binding
4. Create or update conversation binding
5. Insert `channel_inbound_turn`
6. Insert every `channel_inbound_turn_message`
7. Claim unique messages in `channel_inbound_message_dedupe`
8. Dispatch one API `ChannelInboundSessionTurnRequest` containing only newly claimed messages
9. Mark accepted, duplicate, rejected, or failed message rows
10. Attach returned `sessionId` to `channel_inbound_turn` and binding when API accepts

The API dispatch payload must preserve `channelProfileId`, `externalConversationId`, `customerId`, and `assistantId`. API session create-or-reuse uses those fields as the channel active identity and must not collapse channel traffic to `customerId + assistantId`.

If all messages are duplicate, gateway should not call session-runtime.

If some are duplicate and some are new, gateway dispatches one API turn containing only new messages.

Turn status transitions:

- `RECEIVED`: audit rows are stored but dispatch result is not finalized.
- `DUPLICATE`: every message is duplicate; no API call is made.
- `DISPATCHED`: every non-duplicate message was accepted by session-runtime.
- `PARTIALLY_DISPATCHED`: at least one message is duplicate and at least one non-duplicate message was accepted by session-runtime.
- `REJECTED`: session-runtime rejected every non-duplicate message before accepting the turn.
- `FAILED`: gateway could not determine session-runtime acceptance because API dispatch failed, timed out, or failed after dedupe claim.

Message status transitions:

- `RECEIVED`: audit row exists but the message has not reached a final inbound status.
- `DUPLICATE`: message-level dedupe found an earlier accepted claim; do not dispatch this message.
- `ACCEPTED`: session-runtime accepted the message and returned a `sessionMessageId`.
- `REJECTED`: session-runtime rejected the message or its enclosing turn before acceptance.

Recovery rules:

- Retrying the same inbound turn reuses existing `channel_inbound_turn` and `channel_inbound_turn_message` rows by `dedupKey`.
- Gateway must not insert duplicate audit rows for the same `(turn_id, request_index)`.
- Gateway must not dispatch messages already marked `ACCEPTED` or `DUPLICATE`.
- If dispatch failed after dedupe claim but before API response, retry dispatches only rows still in `RECEIVED` for that turn.
- If a retry sees an all-duplicate turn, it finalizes as `DUPLICATE` without calling API.
- If API returns duplicate/accepted ids for retried messages, gateway updates `channel_inbound_message_dedupe.session_id/session_message_id` and message statuses idempotently.
- Provider response for message-class inbound represents synchronous session-runtime acceptance, rejection, duplicate, or failure; it is not an eventual queued state.

## 5. Attachment Mapping

Mapping rules:

- Text-only message -> `TEXT`
- Rich markdown message -> `RICH_TEXT`
- Image attachment -> `IMAGE`
- Non-image attachment -> `CARD` with `cardType = FILE_ATTACHMENT`

File attachment card:

```json
{
  "type": "CARD",
  "cardType": "FILE_ATTACHMENT",
  "version": "1",
  "data": {
    "externalAttachmentId": "att-1",
    "externalFileId": "file-1",
    "fileName": "contract.pdf",
    "mimeType": "application/pdf",
    "url": "https://example.com/contract.pdf",
    "sizeBytes": 10240
  },
  "actions": [
    {
      "actionType": "LINK",
      "label": "Open",
      "url": "https://example.com/contract.pdf"
    }
  ]
}
```

## 6. Channel Outbound Rules

Outbound final replay filters:

- `producer_type = PLATFORM`
- `role in (ASSISTANT, HUMAN_OPERATOR, SYSTEM)`
- final status is deliverable
- non-empty blocks
- active channel binding/profile

Outbound target resolution must use the channel identity attached to the session:

- `channelProfileId`
- `externalConversationId`
- `assistantId`
- `customerId`

The implementation may continue using `channel_session_binding_snapshot` if it remains synchronized with session active identity. It must not infer channel target from only `customerId + assistantId`.

## 7. Web Migration

Web sends:

```ts
export interface SendSessionTurnPayload {
  sessionId?: string | null;
  assistantId?: string | null;
  customerId: string;
  turnDedupKey: string;
  messages: WebSessionTurnMessageInput[];
  metadata?: Record<string, unknown>;
}
```

Rules:

- Frontend creates stable `turnDedupKey` per submit action
- `Idempotency-Key` equals `turnDedupKey`
- Each draft message gets `clientMessageId`
- Web does not send `role/sender`
- First message creates or reuses active Web session
- API response returns `acceptedMessageAllocations[]` with `requestIndex/clientMessageId/messageId/turnIndex`
- Web reconciles user drafts by `clientMessageId -> acceptedMessageAllocations.messageId`; if `clientMessageId` is missing, fallback is `requestIndex`
- `acceptedMessageIds` is a convenience list only and must not be the only draft reconciliation source
- Turn stream payloads expose `replyMessageId`; Web reply drafts are keyed by `(turnId, replyMessageId)`
- Duplicate/idempotent send replay must not create duplicate local bubbles; existing drafts are replaced with the same persisted ids returned by the replayed response
- UI groups by `turnId` only for diagnostics; visible chat remains message-level bubbles
- Same turn multiple input messages render as independent bubbles in request/turn order

## 8. Documentation Updates

Update current docs that still mention old paths:

- `docs/architecture/local-development.md`
- `docs/architecture/extension-integration.md`
- `docs/technical_route.md`
- `docs/project_structure.md`
- `docs/briefing/project_overview.md`

Do not modify `docs/develop_record/` unless specifically asked.
