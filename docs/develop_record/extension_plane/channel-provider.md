# Channel Provider Extension

## 1. 目标

把 channel provider 从硬编码 enum / connector 实现，重构为 registry / descriptor 模型。

`providerType` 目标形态是 string descriptor id：

```text
feishu
enterprise.acme.internal-im
enterprise.acme.ticket
```

## 2. Provider 能力

Channel provider 分两种实现形态：

```text
gateway-native provider
  运行在 channel-gateway 内
  适合开源 reference channel
  webhook 可直接进入 channel-gateway

remote provider service
  运行在企业私有服务内
  适合企业私有、强定制、内网和安全复杂场景
  webhook 先进入 provider service，再推标准事件给 channel-gateway
```

两种形态最终都必须产出同一个 `NormalizedChannelInboundEvent`，并由 `channel-gateway` 统一处理 dedup、binding、event store 和 session-runtime 衔接。

```text
ChannelProvider
  providerType
  definition
  verifyInbound          # gateway-native provider 内部能力，不作为 remote endpoint
  normalizeInbound       # gateway-native provider 内部能力，不作为 remote endpoint
  sendOutbound
  runJob
```

## 3. Provider Manifest

Provider manifest 是 service-level manifest envelope 中的 channel provider descriptor。gateway-native provider 的 descriptor 由 `channel-gateway` 内部 `DescriptorProvider` 产出，并通过 `channel-gateway` 自身的 `/extension/manifest` endpoint 暴露给 API；remote provider service 的 descriptor 通过自身 `/extension/manifest` 暴露。两类来源的 envelope shape、schema 校验和 digest 计算完全一致。

下方仅展示单个 channel provider descriptor 元素；外层 service-level envelope 字段（`extensionApiVersion` / `coreMinVersion` / `coreMaxVersion` / `descriptors.*`）按 `extension-protocol.md §2.1` 补齐。
Channel Provider definition digest 的 canonical object 字段列表以 `static-registration.md §5.1` 为准。

```json
{
  "providerType": "enterprise.acme.internal-im",
  "title": "Acme Internal IM",
  "accountConfigSchema": {},
  "accountConfigUiSchema": [],
  "credentialSchema": {},
  "credentialUiSchema": [],
  "configSchema": {},
  "configUiSchema": [],
  "defaultConfig": {},
  "jobDefinitions": [
    {
      "jobType": "PULL_MESSAGES",
      "title": "Pull messages",
      "description": "Pull missed messages from provider",
      "jobConfigSchema": {},
      "jobConfigUiSchema": [],
      "defaultSchedule": {
        "scheduleType": "INTERVAL",
        "intervalSeconds": 60,
        "cronExpression": null,
        "timezone": "UTC",
        "jobConfig": {}
      },
      "defaultEnabled": false,
      "defaultJobTimeoutSeconds": 60
    }
  ],
  "endpoints": {
    "sendOutbound": "/channel/send-outbound",
    "runJob": "/channel/run-job",
    "createCredential": "/credentials",
    "rotateCredential": "/credentials/rotate",
    "revokeCredential": "/credentials/revoke",
    "validateCredential": "/credentials/validate"
  }
}
```

规则：

1. Channel provider descriptor 不默认强制 Integration Account 或 Core-managed credential；provider 可以完全通过 extension 私有配置 / 私有存储维护 credential
2. `title` 是面向 Web 和管理员展示的用户可读名称，用于快速识别 provider；`description` 如存在则只作为补充说明
3. `credentialSchema` 与 `endpoints.createCredential` / `rotateCredential` / `revokeCredential` / `validateCredential` 是可选能力；一旦声明任一 credential endpoint，就必须同时声明完整四个 endpoint 和 `credentialSchema`
4. gateway-native reference provider 可以声明 `credentialSchema` / `credentialUiSchema` 但不声明 credential endpoints；这种模式使用 Core-owned encrypted reference secret，沿用现有 API 加密存储和 internal runtime resolver，不属于 remote extension protocol
5. credential endpoint 契约引用 `extension-protocol.md §2.2`
6. manifest validation 只做轻量 shape 校验：字段存在性、类型、endpoint path 形态、JSON Schema 自身合法性。credential 是否已配置、账号是否可用、schema 与 extension 私有实现是否语义匹配，不在 registry 阶段拦截，由创建 account、更新 profile 或 runtime invocation 时报错暴露
7. `accountConfigSchema` 描述可选 Integration Account 的非敏感账号配置（identity / tenant / scope 等），不得包含 secret 字段；secret material 只能通过 credential endpoints、Core-owned encrypted reference secret 或 extension 私有配置 / 私有存储维护
8. Integration Account create / update 时，control-plane API 必须按 `CHANNEL_PROVIDER + providerType`
   查找当前 definition registry，并用该 descriptor 的 `accountConfigSchema` 校验
   `integration_account.config`；Web 侧校验只作为体验优化
9. `configSchema` 是 `channel_profile.config` 的 provider-specific 运行配置契约；它与 Integration Account 级的 `accountConfigSchema` 分离，二者不得互相承载对方字段
10. `accountConfigUiSchema` / `credentialUiSchema` / `configUiSchema`
   是对应 JSON Schema 的展示 hint，字段引用、冲突检测和 fixture 规则引用 `extension-protocol.md §2.4`
11. `defaultConfig` 必须通过 `configSchema` 校验
12. `configSchema` / `jobDefinitions[*].jobConfigSchema`
   以及对应 default 不得包含 secret 字段；secret 边界引用 `extension-protocol.md §2.0`
13. Provider manifest 不声明 assistant binding schema、通用 outbound template DSL，也不保存外部模板内容；assistant binding 使用平台固定 schema，外部模板 ID 映射由 `channel_profile_template_binding` 管理
14. `endpoints.sendOutbound` 是所有 channel provider 必填 endpoint；第一版不支持 inbound-only provider
15. `jobDefinitions` 缺省 normalize 为 `[]`；Web / API 直接根据该数组渲染 provider job UI，缺失或为空时展示无 provider job 配置
16. `jobDefinitions` 非空时 `endpoints.runJob` 必填；`jobDefinitions` 为空时 `endpoints.runJob` 不得出现，registry validation 必须拒绝无 job definition 的无意义 `runJob`
17. `endpoints.sendOutbound` 的 key 固定；声明 `runJob` 时也必须使用固定 key `endpoints.runJob`；path 由 manifest 声明，`/channel/send-outbound` / `/channel/run-job` 是推荐示例，不是协议强制值

remote provider manifest 不声明 `verifyInbound` / `normalizeInbound` endpoint。远程 provider 自己接 webhook，并在内部完成验签、解密、challenge、归一化和私有审计。

`jobDefinitions[*]` 的元素 schema 由 `job-definition.schema.json` 定义，字段包括：

```text
jobType
title
description
jobConfigSchema
jobConfigUiSchema
defaultSchedule
defaultEnabled
defaultJobTimeoutSeconds
```

`jobDefinitions[*].jobConfigSchema` 只描述 provider-specific job config。平台通用 schedule 字段由 `schedule-config.schema.json` 定义，不由 provider manifest 改写。
`defaultEnabled` 只作为创建 / 重置 provider job 时的输入默认值，保存时投影成
`channel_profile_job.status = ACTIVE / DISABLED`，不进入持久化的 `schedule_config`，也不进入 Channel Provider definition digest。
`defaultJobTimeoutSeconds` 可省略；它只作为创建 / 重置 provider job 时回填 `schedule_config.jobTimeoutSeconds` 的默认值，不进入 Channel Provider definition digest。缺省时使用 `deployment-and-governance.md §9` 的 `channel runJob` 默认值。

`jobDefinitions[*].jobType` 在同一个 `providerType` descriptor 内必须唯一，跨 provider 不要求全局唯一。Core 持久化和运行时定位 job 时使用 `(providerType, jobType)` 作为语义身份，registry validation 必须拒绝同一 provider descriptor 内重复的 `jobType`。

### 3.0.1 Service / Provider / Profile / Assistant 关系

service 是部署单元，provider descriptor 是 channel 能力类型，`channel_profile` 是具体运行实例。

一个 extension service 可以在同一个 manifest envelope 中暴露多个 channel provider 和多个 tool
connector。管理员可以基于同一个 channel provider 创建多个 `channel_profile`，每个 profile 可以绑定不同的
Integration Account、provider-native identity 范围、assistant / scenario 和 session 复用策略。

示例：

```text
extension service: enterprise.acme.extension
  channel provider: enterprise.acme.internal-im
    channel_profile: sales-support-im
      assistantBinding: { assistantId: "assistant-sales" }
      config: { tenantId: "acme", groupPattern: "sales-*" }

    channel_profile: ops-support-im
      assistantBinding: { assistantId: "assistant-ops" }
      config: { tenantId: "acme", groupPattern: "ops-*" }
```

规则：

1. provider descriptor 不直接绑定 assistant
2. assistant / scenario 路由属于 `channel_profile.assistant_binding`
3. `configSchema` 只描述 provider-specific profile config，例如 tenant、app、群组、
   外部入口、provider-native identity 字段等
4. `assistant_binding`、`accountId`、启停状态等平台通用字段不放进
   `configSchema`
5. 同一个 providerType 下允许多个 profile 映射到不同 assistant
6. inbound normalize 后的最终路由以 `channel-gateway` 本地 `channel_profile` /
   `channel_conversation_binding` 判定为准

## 3.1 Channel Runtime 数据归属

`channel-gateway` 是 channel runtime 数据 owner、provider job scanner owner 和 outbound
delivery owner。API 只提供鉴权后的 Web-facing 管理入口和 definition 聚合入口，不直读
channel runtime 表。

规则：

1. `channel_profile` / `channel_profile_job` / `channel_outbound_delivery` / inbound event /
   conversation binding / template binding 等 channel runtime 表的 migration 和运行时读写归属 `channel-gateway`
2. API 的 channel admin endpoint 必须通过 `channel-gateway` internal API 完成读写
3. `channel-gateway` 不直读 API-owned tables；profile 在创建 / 更新时由 API / channel admin 链路校验已选择的 Integration Account 和 assistant / scenario 引用，并把当时已有的 `externalSecretRef` 写入 channel runtime profile；未选择 account 或未通过 Core 配置 credential 时不写入对应字段
4. 不采用 `channel-gateway` 完全无状态、所有 channel runtime 读写经 API 转发的模式
5. 当前实现中 `channel-gateway` 使用独立 PostgreSQL database / schema 边界，默认 datasource 指向
   `lynxus_channel_gateway`，不与 API / worker 的 `lynxus_core` 共享 `public` schema
6. `channel-gateway` Flyway history table 固定为 `channel_gateway_schema_history`，不复用 API 的
   `flyway_schema_history`
7. `channel-gateway` 自己维护 jOOQ codegen 和 generated schema，生成包为
   `com.lynxus.channel.gateway.jooq`，只读取 `apps/channel-gateway/src/main/resources/db/migration`
8. `packages/persistence-jvm` 是 API / worker 的公共 persistence 模块，只读取 API migration，不包含
   channel runtime jOOQ schema、channel runtime repository 或 channel business store
9. 如果某些部署把 API、worker、`channel-gateway` 放在同一个物理 PostgreSQL 实例中，也必须保持
   `channel-gateway` 的 database / schema / Flyway history 隔离，不允许 channel runtime tables 落入
   API-owned schema
10. `channel-gateway` 多副本部署语义固定为 active-active；不设置 primary / standby，不做分片调度，也不由 Temporal 接管 channel runtime 调度
11. Postgres 是 channel runtime state 权威；Redis lock 只用于 provider job 多副本去重，outbound delivery 不使用 Redis lock
12. 滚动重启、in-flight invocation 和 provider job lock TTL 语义引用 `deployment-and-governance.md §1.1`

### 3.2 Channel Gateway Internal Admin Contract

Channel admin 有两层契约：

1. Web-facing API 契约由 `packages/contracts/openapi/control-plane.yaml` 维护，路径前缀为 `/api/channel-admin/*`
2. API -> `channel-gateway` internal 契约由 `packages/contracts/openapi/channel-gateway-internal.yaml` 维护，路径前缀为 `/internal/channel-admin/*`

API 是鉴权、租户边界、审计、Integration Account 校验和 assistant / scenario 引用校验 owner。`channel-gateway` 是 runtime aggregate owner，只信任 internal DTO 中已经物化好的 profile / binding / job 配置，不直读 API-owned table。assistant / scenario 引用只在 profile create / update 边界由 API 校验；保存后的 assistant / scenario 归档、删除或不可发布状态不由 `channel-gateway` 主动感知。

Profile internal endpoints：

```text
GET    /internal/channel-admin/profiles
POST   /internal/channel-admin/profiles
GET    /internal/channel-admin/profiles/{channelProfileId}
PUT    /internal/channel-admin/profiles/{channelProfileId}
DELETE /internal/channel-admin/profiles/{channelProfileId}
```

Profile write DTO：

```json
{
  "providerType": "enterprise.acme.internal-im",
  "displayName": "Sales Support IM",
  "inboundEnabled": true,
  "config": {},
  "assistantBinding": {
    "assistantId": "assistant-xxx",
    "scenarioId": null
  },
  "accountSnapshot": {
    "accountId": "integration-account-xxx",
    "externalSecretRef": "vault://..."
  },
  "expectedRevision": 3
}
```

Profile contract rules：

1. `channelProfileId` 由 `channel-gateway` 生成并在创建响应中返回
2. `providerType` 必须是 `channel-gateway` registry 中已加载的 channel provider descriptor id
3. `config` 由 `configSchema` 校验；缺省时 `channel-gateway` 按 provider definition 的 `defaultConfig` 回填
4. `assistantBinding` 只按平台固定 schema 校验；assistant / scenario 是否存在、可见、可发布由 API 在调用 internal endpoint 前完成
5. `accountSnapshot` 整体可为 `null`；`accountId` 和 `externalSecretRef` 均可为空，不作为 gateway 协议错误
6. API 只在 Integration Account 当前已有 `externalSecretRef` 时写入该字段；credential 未通过 Core 配置时不写入
7. `externalSecretRef` 可以在 write DTO 中进入 `channel-gateway`，但 internal read DTO 不返回原文，只返回 `accountId` 与 `hasExternalSecretRef`
8. `POST` 创建 profile 时不提交 `expectedRevision`；`PUT` / `DELETE` 必须提交 `expectedRevision` 防止 Web / API stale overwrite，不匹配时 `channel-gateway` 返回 conflict
9. `DELETE` 表达 archive / disable，不物理删除已有 delivery、event、job run history
10. `channel-gateway` 不维护 assistant / scenario invalidation，也不在 ingest / outbound 路径回 API 做实时引用校验；后续对话或 control-plane 操作发现 assistant / scenario 已失效时，由 API 返回结构化业务错误，调用方据此提示用户重新选择或恢复配置
11. `sendOutbound` 是 provider 必备能力；profile 不提供 `outboundEnabled`，整体停用使用 `channel_profile.status`，单个 outbound 映射停用使用 `channel_profile_template_binding.enabled`

Profile read DTO：

```json
{
  "channelProfileId": "channel-profile-xxx",
  "providerType": "enterprise.acme.internal-im",
  "displayName": "Sales Support IM",
  "inboundEnabled": true,
  "config": {},
  "assistantBinding": {
    "assistantId": "assistant-xxx",
    "scenarioId": null
  },
  "accountId": "integration-account-xxx",
  "hasExternalSecretRef": true,
  "status": "ACTIVE",
  "revision": 4,
  "createdAt": "2026-04-28T00:00:00Z",
  "updatedAt": "2026-04-28T00:00:00Z"
}
```

Template binding internal endpoints：

```text
GET    /internal/channel-admin/profiles/{channelProfileId}/template-bindings
PUT    /internal/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}
DELETE /internal/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}
```

Template binding write DTO：

```json
{
  "externalTemplateId": "tpl_123",
  "externalTemplateVersion": "v1",
  "variableSchema": {},
  "displayName": "Order status card",
  "externalEditUrl": "https://provider.example.com/templates/tpl_123",
  "enabled": true,
  "expectedRevision": 2
}
```

Template binding rules：

1. 路径中的 `assistantId + channelProfileId + messageType + messageSubtype + messageVersion` 是 binding natural key
2. API 在调用 internal endpoint 前校验 assistant 可见性和 message type / subtype / version 是否来自平台支持的 message block
3. `channel-gateway` 只保存 provider-native opaque template id / version，不解析 provider template body
4. `variableSchema` 不得包含 secret 字段、credential 明文、`externalSecretRef` 或 provider-native secret reference
5. 更新只影响后续 outbound delivery；已创建 delivery 使用创建时快照里的 `resolvedTemplate`

Provider job internal endpoints：

```text
GET    /internal/channel-admin/profiles/{channelProfileId}/jobs
PUT    /internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}
DELETE /internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}
POST   /internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
GET    /internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
```

Provider job write DTO：

```json
{
  "scheduleConfig": {
    "enabled": true,
    "scheduleType": "CRON",
    "intervalSeconds": null,
    "cronExpression": "0 * * * *",
    "timezone": "Asia/Shanghai",
    "jobTimeoutSeconds": 60,
    "jobConfig": {}
  },
  "expectedRevision": 5
}
```

Provider job read DTO：

```json
{
  "jobType": "PULL_MESSAGES",
  "status": "ACTIVE",
  "scheduleConfig": {
    "scheduleType": "CRON",
    "intervalSeconds": null,
    "cronExpression": "0 * * * *",
    "timezone": "Asia/Shanghai",
    "jobTimeoutSeconds": 60,
    "jobConfig": {}
  },
  "nextRunAt": "2026-04-28T01:00:00Z",
  "lastRunAt": null,
  "lastSuccessAt": null,
  "lastError": null,
  "failureCount": 0,
  "revision": 5
}
```

Provider job read DTO 的 `scheduleConfig` 来自已持久化的 `channel_profile_job.schedule_config`，
因此不包含 `enabled`；启用、禁用、暂停和运行中状态只看顶层 `status`。

Provider job rules：

1. `jobType` 必须来自 provider manifest 的 `jobDefinitions[*].jobType`
2. `scheduleConfig` 的平台字段由 provider job write DTO schema 校验；其中 `enabled` 是写入输入字段，不进入持久化的 `channel_profile_job.schedule_config`
3. `scheduleConfig.jobConfig` 由对应 `jobDefinitions[*].jobConfigSchema` 校验
4. 创建 / 更新 job 时，`channel-gateway` 先 normalize `scheduleConfig` 缺省值，再把 `scheduleConfig.enabled` 投影成 `channel_profile_job.status = ACTIVE / DISABLED`，最后保存不含 `enabled` 的 `schedule_config`
5. 创建 job 时未提交 `scheduleConfig`，`channel-gateway` 使用 manifest 中的 `defaultSchedule` / `defaultEnabled` / `defaultJobTimeoutSeconds`；`defaultEnabled` 同样只投影成初始 `status`
6. `channel_profile_job.status` 是运行态事实源；自动调度、手动触发、暂停和禁用判断都不得读取持久化 `schedule_config.enabled`
7. job 处于 `RUNNING` 时拒绝配置更新、启用、禁用或暂停，返回 409，避免在单个 `status` 字段里同时表达运行中和目标禁用态
8. 手动触发 run 复用 provider job 的 Redis lock 和 `RUNNING` 状态机；job 已在运行、lock 已被持有或 `status != ACTIVE` 时返回 409，不创建新的 run
9. 手动触发 run 只在成功 claim 后创建一次 `channel_profile_job_run`，并由 `channel-gateway` 生成 `idempotencyKey`
10. API 不创建 run id、不计算 Redis lock key、不推进 job status

Internal error mapping：

| 场景 | HTTP | code |
| --- | --- | --- |
| profile / binding / job 不存在 | 404 | `CHANNEL_RUNTIME_NOT_FOUND` |
| providerType 或 jobType 未注册 | 422 | `CHANNEL_PROVIDER_DEFINITION_INVALID` |
| config / assistantBinding / scheduleConfig schema 校验失败 | 422 | `CHANNEL_RUNTIME_CONFIG_INVALID` |
| job 已处于 `RUNNING` 或 Redis lock 已被持有 | 409 | `CHANNEL_PROVIDER_JOB_RUNNING` |
| job 为 `DISABLED` / `PAUSED`，不能自动 claim 或手动触发 | 409 | `CHANNEL_PROVIDER_JOB_NOT_ACTIVE` |
| `expectedRevision` 不匹配 | 409 | `CHANNEL_RUNTIME_REVISION_CONFLICT` |
| internal auth 失败 | 401 / 403 | `INTERNAL_AUTH_FAILED` |

### 3.3 Channel Admin Web-facing Contract

Web-facing Channel Admin API 是 control-plane API，事实源是
`packages/contracts/openapi/control-plane.yaml`。它只面向 Web / 管理员，API 在这一层完成鉴权、
租户边界、审计、assistant / scenario 引用校验、Integration Account 校验和 DTO 映射。
`channel-gateway` internal API 只接收已经物化好的 runtime DTO。

Web-facing endpoints：

```text
GET    /api/channel-admin/profiles
POST   /api/channel-admin/profiles
GET    /api/channel-admin/profiles/{channelProfileId}
PUT    /api/channel-admin/profiles/{channelProfileId}
DELETE /api/channel-admin/profiles/{channelProfileId}?expectedRevision={revision}

GET    /api/channel-admin/profiles/{channelProfileId}/template-bindings
PUT    /api/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}
DELETE /api/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}?expectedRevision={revision}

GET    /api/channel-admin/profiles/{channelProfileId}/jobs
PUT    /api/channel-admin/profiles/{channelProfileId}/jobs/{jobType}
DELETE /api/channel-admin/profiles/{channelProfileId}/jobs/{jobType}?expectedRevision={revision}
POST   /api/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
GET    /api/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
```

Profile Web-facing write DTO：

```json
{
  "providerType": "enterprise.acme.internal-im",
  "displayName": "Sales Support IM",
  "inboundEnabled": true,
  "config": {},
  "assistantBinding": {
    "assistantId": "assistant-xxx",
    "scenarioId": null,
    "bindingScope": "CONVERSATION",
    "customerIdentitySource": "EXTERNAL_USER_ID",
    "sessionPolicy": {
      "mode": "REUSE_ACTIVE_OR_CREATE",
      "idleTtlSeconds": 86400
    }
  },
  "accountId": "integration-account-xxx",
  "expectedRevision": 3
}
```

Profile Web-facing read DTO：

```json
{
  "channelProfileId": "channel-profile-xxx",
  "providerType": "enterprise.acme.internal-im",
  "displayName": "Sales Support IM",
  "inboundEnabled": true,
  "config": {},
  "assistantBinding": {
    "assistantId": "assistant-xxx",
    "scenarioId": null,
    "bindingScope": "CONVERSATION",
    "customerIdentitySource": "EXTERNAL_USER_ID",
    "sessionPolicy": {
      "mode": "REUSE_ACTIVE_OR_CREATE",
      "idleTtlSeconds": 86400
    }
  },
  "integrationAccount": {
    "accountId": "integration-account-xxx",
    "name": "Acme IM bot",
    "status": "ENABLED",
    "credentialStatus": "ACTIVE",
    "hasExternalSecretRef": true
  },
  "status": "ACTIVE",
  "revision": 4,
  "createdAt": "2026-04-28T00:00:00Z",
  "updatedAt": "2026-04-28T00:00:00Z"
}
```

Profile Web-facing rules：

1. Web-facing write DTO 提交 `accountId`，不提交 `externalSecretRef`
2. `accountId` 可为 `null`；Channel Provider 不默认强制绑定 Integration Account
3. API 在调用 internal profile create / update 前校验 Integration Account 的 `subjectType = CHANNEL_PROVIDER`、`subjectId = providerType`、`status = ENABLED`，并拒绝 `REVOKE_FAILED` / `REVOKED`
4. API 只在 account 当前已有 `externalSecretRef` 时物化到 internal `accountSnapshot.externalSecretRef`
5. Web-facing read DTO 不返回 `externalSecretRef` 原文，只返回 `hasExternalSecretRef` 与可展示的 account / credential 状态
6. `credentialStatus` 只作为风险提示；除撤销态外，不阻止保存、启用或发布，运行时失败由 extension 边界暴露
7. `POST` 创建 profile 时不提交 `expectedRevision`；`PUT` 必须提交 `expectedRevision`；`DELETE` 用 query `expectedRevision` 传递版本
8. API 必须在 create / update 时校验 `assistantBinding.assistantId` 存在、未归档、可发布且当前操作者可见；`scenarioId` 如填写，也必须属于该 assistant 且可用
9. API 把 gateway internal error 映射为 control-plane error，不透出 internal URL、internal token、`externalSecretRef` 或 gateway 原始异常明文

Template binding Web-facing write DTO 与 internal DTO 字段一致，但仍由 API 做权限和 message block
类型校验：

```json
{
  "externalTemplateId": "tpl_123",
  "externalTemplateVersion": "v1",
  "variableSchema": {},
  "displayName": "Order status card",
  "externalEditUrl": "https://provider.example.com/templates/tpl_123",
  "enabled": true,
  "expectedRevision": 2
}
```

Template binding Web-facing rules：

1. API 在调用 internal endpoint 前校验 `assistantId` 对当前操作者可见
2. API 校验 `messageType + messageSubtype + messageVersion` 来自平台支持的 message block
3. `variableSchema`、`displayName`、`externalEditUrl` 不得包含 secret、credential 明文、`externalSecretRef` 或可还原 secret material
4. `DELETE` 用 query `expectedRevision` 传递版本

Provider job Web-facing write DTO 与 internal DTO 字段一致：

```json
{
  "scheduleConfig": {
    "enabled": true,
    "scheduleType": "CRON",
    "intervalSeconds": null,
    "cronExpression": "0 * * * *",
    "timezone": "Asia/Shanghai",
    "jobTimeoutSeconds": 60,
    "jobConfig": {}
  },
  "expectedRevision": 5
}
```

Provider job Web-facing rules：

1. API 根据 provider definition 校验 `jobType` 存在
2. API / gateway 都必须校验 provider job write DTO；gateway 最终按 runtime registry 中的 provider `jobConfigSchema` 校验 `jobConfig`
3. 手动触发 run 的 Web-facing API 不接受客户端传入 run id、Redis lock key 或 `idempotencyKey`
4. `POST /runs` 由 channel-gateway claim job；如果 job 已处于 `RUNNING` 或 Redis lock 已被持有，API 返回 409 `CHANNEL_PROVIDER_JOB_RUNNING`；如果 job 为 `DISABLED` / `PAUSED`，API 返回 409 `CHANNEL_PROVIDER_JOB_NOT_ACTIVE`；均不创建新的 run
5. `POST /runs` 返回 channel-gateway 创建的 run 记录；Web 通过 `GET /runs` 查看历史
6. `scheduleConfig.enabled` 只作为 Web-facing 写入输入；保存后的启用状态以 read DTO 中的 `status` 为准
7. `DELETE` 用 query `expectedRevision` 传递版本

## 4. Inbound Webhook 模式

### 4.1 Gateway-native provider

```text
external webhook
  -> channel-gateway fixed provider public endpoint
    -> gateway-native provider adapter
      -> verify / decrypt / resolve channelProfileId / normalize
        -> NormalizedChannelInboundEvent
          -> dedup / binding / event store
          -> session-runtime
```

gateway-native provider 不使用全局 channel routing token。每个 provider 暴露固定 public endpoint，
例如：

```text
POST /webhooks/channel/feishu
```

`channelProfileId` 由 provider adapter 根据 provider-native identity 解析，例如 app id、
tenant id、chat id、external channel id 或 provider payload / header 中的稳定身份字段。

### 4.2 Remote provider service

```text
external webhook
  -> enterprise provider service
    -> verify / decrypt / normalize / private audit
      -> channel-gateway internal normalized event API
        -> dedup / binding / event store
        -> session-runtime
```

适合：

1. 内部 IM
2. 私有工单系统
3. 复杂签名 / 解密 / challenge
4. 需要访问企业内网、Vault、审计系统
5. 客户不希望 webhook 直接打到 Lynxus core

### 4.3 Channel Gateway Internal Endpoint

remote provider 通过 internal endpoint 推标准事件：

```text
POST /internal/channel-events/normalized
```

remote provider 必须在调用 internal endpoint 前解析出目标 `channelProfileId`。

remote provider 自己拥有 public webhook URL 和路由机制。Core 不生成、不保存、不轮换 remote
provider 的 public webhook routing token。

规则：

1. `channelProfileId` 是 Core 侧 channel runtime profile 的权威 id
2. remote provider 不得用 `integration_account.id` 替代 `channelProfileId`
3. remote provider 必须通过 public webhook path、provider-native identity 或私有映射解析 `channelProfileId`
4. `channel-gateway` 必须以本地 `channel_profile` 记录做最终校验和授权
5. 无法解析目标 `channelProfileId` 的 inbound event 不得推给 Core，应在 provider 私有审计中记录并按 provider 策略处理

Channel Profile 与 remote provider 的映射建立规则：

1. Core 不向 remote provider 自动推送 profile snapshot，也不保存 remote provider 的 public webhook routing token
2. 创建 Channel Profile 后，API / Web 必须展示稳定的 `channelProfileId`，供 operator 配置外部系统 webhook 或 provider 私有路由
3. 首选方式是 remote provider 的 public webhook URL path 直接携带 `channelProfileId`，例如：

```text
POST https://{provider-public-host}/webhooks/lynxus/profiles/{channelProfileId}
```

4. `channelProfileId` 不是 secret，不能替代外部 webhook 鉴权；remote provider 仍必须校验外部系统 webhook 的签名、token、source IP 或私有 signing secret
5. 如果外部系统支持为每个对象配置独立 callback URL，operator 应把包含 `channelProfileId` 的 webhook URL 填到外部系统对象上，例如 IM 群、机器人、inbox、queue 或 project
6. 如果外部系统只支持一个全局 webhook endpoint，或 webhook URL 不能按对象区分，remote provider 才维护私有映射表，例如 `externalTenant + externalInbox -> channelProfileId`
7. provider-native identity（例如 tenant、bot、group、webhook path alias）可以保存在 `channel_profile.config` 或 provider 私有映射表中；两边的共同键必须是非敏感值
8. 如果映射所需字段属于 Core 配置面，由 `configSchema` 描述并保存到 `channel_profile.config`
9. 如果映射涉及 provider 私有系统或 secret，只能在 provider 私有配置 / 私有管理入口 / 私有存储中维护，Core 只保存 `channelProfileId` 和非敏感 config
10. 远程 provider 推事件时必须提交 Core 侧 `channelProfileId`；`channel-gateway` 不根据 external identity 反查 remote provider 私有映射
11. 第一版不定义 Core -> provider 的 mapping 管理 API、mapping status API 或 dynamic test endpoint
12. 第一版不在 Core 保存 `inboundVerificationStatus` / `webhookVerificationStatus`；启用 inbound 不因外部 webhook 尚未验证而阻塞
13. 映射验证通过 runbook / smoke check 中的 test normalized event 完成：provider 发送一条带 `channelProfileId` 的 normalized event，`channel-gateway` 校验 profile、providerType、auth、assistant binding 和 dedup 后返回结果
14. Web 可以展示“未由 Core 验证”的配置提示和 test event 指引，但该提示不是持久状态，不进入 `channel_profile` 表、DTO 或 release/snapshot

请求体：

```json
{
  "providerType": "enterprise.acme.internal-im",
  "channelProfileId": "channel-profile-xxx",
  "eventType": "MESSAGE_RECEIVED",
  "externalEventId": "evt-1",
  "externalConversationId": "chat-1",
  "externalMessageId": "msg-1",
  "externalUserId": "user-1",
  "occurredAt": "2026-04-25T00:00:00Z",
  "dedupKey": "enterprise.acme.internal-im:message:msg-1",
  "conversation": {
    "externalConversationId": "chat-1",
    "type": "GROUP",
    "title": "Support Group",
    "metadata": {}
  },
  "sender": {
    "externalUserId": "user-1",
    "displayName": "Alice",
    "metadata": {}
  },
  "message": {
    "type": "TEXT",
    "text": "hello",
    "attachments": [],
    "metadata": {}
  },
  "normalizedPayload": {},
  "rawPayload": {},
  "traceContext": {
    "traceparent": "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
  },
  "metadata": {}
}
```

remote provider 推送 normalized event 时必须提供 `dedupKey`。

`dedupKey` 字段格式必须满足 `extension-protocol.md §2.0` 中 `Idempotency-Key` 的同一套规则（ASCII charset `[A-Za-z0-9._:-]`，长度 1-128，大小写敏感），`channel-gateway` 兜底 hash 计算后的值同样落在此约束内。

`channel-gateway` 的兜底 hash 只适用于 gateway-native provider adapter 在进程内 normalize 后未显式提供 dedup hint 的情况；remote normalized event API 不接受缺失 `dedupKey` 的请求。

`NormalizedChannelInboundEvent` 字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `providerType` | 是 | manifest 中的 channel provider descriptor id |
| `channelProfileId` | 是 | 目标 channel runtime profile id |
| `eventType` | 是 | 标准事件类型 |
| `dedupKey` | 是 | 幂等键。两种来源分别处理：① remote provider 调 `/internal/channel-events/normalized` 时必填，缺失直接拒绝；② gateway-native adapter 在进程内构造 event 时若未填，由 `channel-gateway` 兜底 hash 计算后写入。任一来源都不允许在 ingest pipeline 后段保持空。字段格式见上文 §4.3 引用的 §2.0 规则 |
| `externalEventId` | 否 | 外部系统事件 id |
| `externalConversationId` | 否 | 外部会话 id |
| `externalMessageId` | 否 | 外部消息 id |
| `externalUserId` | 否 | 外部用户 id |
| `occurredAt` | 否 | 外部事件发生时间 |
| `conversation` | 否 | 标准化会话信息 |
| `sender` | 否 | 标准化发送人 / actor 信息 |
| `message` | 否 | 标准化消息内容 |
| `normalizedPayload` | 是 | 标准化扩展 payload，默认为 `{}` |
| `rawPayload` | 否 | 已脱敏原始 payload；不得包含 credential、token、签名密钥或内部私密字段 |
| `traceContext` | 是 | trace 透传信息，必须包含 `traceparent` |
| `metadata` | 否 | 非敏感扩展元数据 |

标准 `eventType`：

```text
MESSAGE_RECEIVED
MESSAGE_UPDATED
MESSAGE_DELETED
CONVERSATION_UPDATED
MEMBER_JOINED
MEMBER_LEFT
REACTION_ADDED
FILE_RECEIVED
WEBHOOK_VERIFIED
UNKNOWN
```

所有 `NormalizedChannelInboundEvent` 都必须满足共同 envelope：

1. `providerType`
2. `channelProfileId`
3. `eventType`
4. `dedupKey`
5. `normalizedPayload`
6. `traceContext`

`eventType` 字段矩阵：

| eventType | conversation | sender | message | 关键外部 id |
| --- | --- | --- | --- | --- |
| `MESSAGE_RECEIVED` | 必填 | 必填 | 必填 | `externalConversationId`、`externalMessageId` 必填 |
| `FILE_RECEIVED` | 必填 | 必填 | 必填，且 `attachments` 非空 | `externalConversationId`、`externalMessageId` 必填 |
| `MESSAGE_UPDATED` | 必填 | 可选 | 必填 | `externalConversationId`、`externalMessageId` 必填 |
| `MESSAGE_DELETED` | 必填 | 可选 | 可选 | `externalConversationId`、`externalMessageId` 必填 |
| `CONVERSATION_UPDATED` | 必填 | 可选 | 禁止 | `externalConversationId` 必填 |
| `MEMBER_JOINED` | 必填 | 必填，表示加入成员 | 禁止 | `externalConversationId`、`externalUserId` 必填 |
| `MEMBER_LEFT` | 必填 | 必填，表示离开成员 | 禁止 | `externalConversationId`、`externalUserId` 必填 |
| `REACTION_ADDED` | 必填 | 必填，表示反应发起人 | 可选 | `externalConversationId`、`externalMessageId`、`externalUserId` 必填 |
| `WEBHOOK_VERIFIED` | 可选 | 禁止 | 禁止 | 不要求 message / conversation id |
| `UNKNOWN` | 可选 | 可选 | 可选 | 尽量提供可用 external id |

一致性规则：

1. `conversation.externalConversationId` 和顶层 `externalConversationId` 同时存在时必须相等
2. `sender.externalUserId` 和顶层 `externalUserId` 同时存在时必须相等
3. `message.externalMessageId` 和顶层 `externalMessageId` 同时存在时必须相等
4. 会创建或推进 session 的事件至少包括 `MESSAGE_RECEIVED` / `FILE_RECEIVED`，必须具备
   `conversation`、`sender` 和 `message`
5. `WEBHOOK_VERIFIED` 是控制事件，只用于记录 webhook challenge / verification，不进入 session
   message 流
6. `UNKNOWN` 只能作为兜底接收，不应该触发强业务动作；是否进入 session 由 binding /
   provider-specific policy 决定
7. 当前 DTO 中 `sender` 在成员事件里表示事件主体成员，在 `REACTION_ADDED` 里表示反应发起人；
   未来如需同时表达操作者和目标成员，再新增 `actor` / `member` 字段

`NormalizedChannelInboundEvent` 是 OpenAPI 中 internal normalized event API 的权威 DTO。Java / Python SDK 的 DTO 必须从该契约生成或被 contract tests 约束。

敏感数据规则：

1. `rawPayload` 只能保存脱敏后的原始 payload
2. 未脱敏原文只能保存在 extension 私有审计 / 存储中；Core 不接收、不保存 raw payload / raw error / raw response 的 opaque ref，也不通过协议反查原文
3. 跨边界排障只使用 `traceContext.traceparent`、`X-Lynxus-Trace-Id`、`X-Lynxus-Request-Id`、`providerType`、`channelProfileId`、`dedupKey` 等 correlation 字段拼接 Core 日志和 extension 私有日志
4. `normalizedPayload` / `metadata` 不得携带 credential、token、签名密钥或外部系统私密字段

内部鉴权规则：

1. remote provider 调 `POST /internal/channel-events/normalized` 必须使用 Extension Plane 统一 internal auth 机制
2. 请求必须带 `Authorization`、`X-Lynxus-Extension-Registration-Id`、`X-Lynxus-Extension-Descriptor-Type: CHANNEL_PROVIDER`、`X-Lynxus-Extension-Descriptor-Id`、`X-Lynxus-Trace-Id`、`X-Lynxus-Request-Id` 和 `Idempotency-Key`
3. `Idempotency-Key` 必须严格等于 event `dedupKey`，字段格式引用 `extension-protocol.md §2.0`
4. `channel-gateway` 必须校验 `registrationId` 与 `providerType` 属于当前静态注册白名单
5. `channel-gateway` 必须校验 `channelProfileId` 存在、启用，且其 `provider_type` 等于请求中的 `providerType`
6. `channel-gateway` 使用本地 channel runtime profile 中固化的可选 `accountId` 做内部校验 / resolver；`NormalizedChannelInboundEvent` request 不携带 `accountId` 或 `externalSecretRef`
7. normalized event header / body 不得携带 credential 明文、`externalSecretRef` 或其他 secret reference；只有 `sendOutbound` / `runJob` 的 remote invocation envelope 顶层允许携带可选 `externalSecretRef`

Assistant binding 与 conversation binding 规则：

1. remote provider 只负责归一化事件并提交 `channelProfileId`
2. `channel-gateway` 读取本地 `channel_profile.assistant_binding`，在 ingest pipeline 中解析或创建 conversation binding
3. `assistant_binding` 不进入 remote normalized event request，remote provider 不能成为 assistant / session routing 的事实源
4. `assistant_binding` 使用平台固定 schema，不接受 provider 自定义 DSL、表达式或脚本
5. gateway-native provider 只提供 normalized event 所需的 `externalConversationId`、`externalUserId` 等标准字段；最终绑定仍由 `channel-gateway` 基于本地 profile / binding 表决定
6. `assistant_binding.assistantId` 的存在性、可见性和可发布性由 API / control-plane 在创建或更新 Channel Profile 时校验；`channel-gateway` 不直读 assistant / catalog 表，也不在入站事件路径回 API 实时校验
7. Channel Profile 启用 inbound 时，`assistant_binding.assistantId` 必须引用一个当前可发布、未归档、调用者有权限访问的 assistant；`scenarioId` 如填写，也必须属于该 assistant 的可用场景
8. 已保存的 `assistant_binding` 是 channel runtime routing snapshot；assistant / scenario 后续被归档、删除或不可发布时，`channel-gateway` 不主动感知、不订阅 invalidation、不回 API 做实时校验
9. 后续对话、发布、管理操作或其他 control-plane owned 入口再次引用该 assistant / scenario 时，由 API 重新校验并返回结构化业务错误；Web / 调用方据此提示用户重新选择 assistant / scenario 或修复配置
10. 因后续失效产生的 API 业务错误不得被解释为 provider credential、provider network 或 extension protocol failure；它属于 profile / assistant 配置问题

`assistant_binding` 固定结构：

```json
{
  "assistantId": "assistant-xxx",
  "scenarioId": "scenario-xxx",
  "bindingScope": "CONVERSATION",
  "customerIdentitySource": "EXTERNAL_USER_ID",
  "sessionPolicy": {
    "mode": "REUSE_ACTIVE_OR_CREATE",
    "idleTtlSeconds": 86400
  }
}
```

字段：

1. `assistantId` 是当前 profile 默认路由 assistant，启用 inbound 时必填
2. `scenarioId` 可选
3. `bindingScope` 取值：`CONVERSATION` / `USER_IN_CONVERSATION` / `USER`
4. `customerIdentitySource` 取值：`EXTERNAL_USER_ID` / `EXTERNAL_CONVERSATION_ID` / `COMPOSITE_CONVERSATION_USER`
5. `sessionPolicy.mode` 取值：`REUSE_ACTIVE_OR_CREATE` / `ALWAYS_CREATE` / `REUSE_UNTIL_IDLE_TIMEOUT`
6. `sessionPolicy.idleTtlSeconds` 仅在 `REUSE_UNTIL_IDLE_TIMEOUT` 下生效

### 4.4 Gateway-native Provider Adapter

Gateway-native provider 运行在 `channel-gateway` 内，通过内部 adapter 接口接入。它不需要暴露 remote `verifyInbound` / `normalizeInbound` endpoint，因为 webhook 直接进入 `channel-gateway`，验签 / normalize 在进程内完成。

目标接口：

```text
GatewayNativeChannelProviderAdapter
  providerType()
  descriptor()
  createCredential(request)       # only when descriptor declares credential endpoints
  rotateCredential(request)       # only when descriptor declares credential endpoints
  revokeCredential(request)       # only when descriptor declares credential endpoints
  validateCredential(request)     # only when descriptor declares credential endpoints
  verifyInbound(request, channelProfile, integrationAccount)
  normalizeInbound(request, verification, channelProfile, integrationAccount)
  sendOutbound(request)
  runJob(request)                 # only when descriptor jobDefinitions is non-empty
```

职责：

1. `providerType()` 返回 manifest descriptor id
2. `descriptor()` 返回 provider descriptor，与 `channel-gateway` `/extension/manifest` 中本 provider 对应的 envelope 元素 byte-for-byte 一致
3. 只有 descriptor 声明 credential endpoints 时，`createCredential(...)` / `rotateCredential(...)` / `revokeCredential(...)` / `validateCredential(...)` 才处理 provider-specific credential material，并返回 `extension-protocol.md §2.2` 的 credential lifecycle envelope
4. `verifyInbound(...)` 完成签名、challenge、解密和来源校验
5. `normalizeInbound(...)` 产出一个或多个 `NormalizedChannelInboundEvent`
6. `sendOutbound(...)` 执行 outbound 远端发送
7. `runJob(...)` 执行 provider job

`channel-gateway` 内部 `DescriptorProvider` 聚合所有 `GatewayNativeChannelProviderAdapter.descriptor()`，对外通过 `/extension/manifest` 暴露，对内被本服务 `ChannelProviderRegistry` 直接读。两条出口共用同一份序列化结果。

gateway-native adapter 与 remote provider service 产出的业务 DTO 必须一致。差异只在执行位置（in-process 直接调用 adapter 方法 vs HTTP 调用 remote endpoint），不在协议语义。

gateway-native reference provider 没有独立 extension 私有存储时，使用 Core-owned encrypted reference secret 和 internal credential resolver 获取明文 credential。该路径沿用现有 API 加密存储实现，只服务 core in-process reference implementation，不属于 remote Channel Provider invocation protocol，也不生成 `externalSecretRef`。

如果 gateway-native provider descriptor 声明 credential endpoints，`channel-gateway` 公共框架负责把 manifest-declared credential endpoint 暴露成 HTTP endpoint，并按 descriptor dispatch 到对应 adapter 的 credential lifecycle 方法；adapter 不自行声明额外 credential URL，也不绕过统一 auth、trace 和审计规则。credential lifecycle 不使用 `Idempotency-Key`。未声明 credential endpoints 的 gateway-native provider 由 Core-owned encrypted reference secret、自身配置或私有存储负责 credential。

## 5. Outbound Delivery

Outbound 始终由 `channel-gateway` 维护投递状态。

```text
session-runtime
  -> channel-gateway outbound delivery
    -> provider send-outbound
      -> external channel
```

remote provider endpoint：

```text
POST {provider.endpoints.sendOutbound}
```

请求 envelope：

```json
{
  "providerType": "enterprise.acme.internal-im",
  "channelProfileId": "channel-profile-xxx",
  "config": {},
  "externalSecretRef": "vault://...",
  "idempotencyKey": "channel-outbound-delivery-xxx",
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  },
  "payload": {
    "externalConversationId": "chat-1",
    "messageBlock": {
      "type": "CARD",
      "cardType": "ORDER_STATUS",
      "version": "1",
      "data": {
        "orderNo": "A001",
        "status": "SHIPPED"
      },
      "actions": []
    },
    "resolvedTemplate": {
      "messageType": "CARD",
      "messageSubtype": "ORDER_STATUS",
      "messageVersion": "1",
      "externalTemplateId": "ctp_xxx",
      "externalTemplateVersion": "published"
    }
  }
}
```

`externalSecretRef`、`idempotencyKey` 和 `traceContext` 的字段规则引用 `extension-protocol.md §2.0`。`externalSecretRef` 是可选字段，仅当 channel runtime profile 已固化 Core-managed credential ref 时发送。`payload.messageBlock` 是 Lynxus session message block，不是 provider-native payload。`resolvedTemplate` 只在该 message type / subtype 需要外部模板时出现。

External template binding 与 outbound 转换边界：

1. Assistant / session message 只产出语义 block，例如 `CARD` block 的 `cardType`、`version`、`data` 和 `actions`；不得携带 `templateKey`、`externalTemplateId` 或 provider-native payload
2. `channel-gateway` 创建 outbound delivery 时，按 `assistantId + channelProfileId + messageType + messageSubtype + messageVersion` 解析 `channel_profile_template_binding`
3. 找不到 enabled binding、变量校验失败或 message type 不被当前 channel 支持时，属于 `channel-gateway` delivery validation failure，不调用 provider
4. delivery payload 必须快照 `messageBlock` 和解析出的 `resolvedTemplate`；binding 后续变更只影响新的 delivery
5. gateway-native / 内置 provider 的 provider-native payload 转换由 `channel-gateway` 内的 provider adapter 负责
6. remote provider 的 provider-native payload 转换由 extension 自己负责；`channel-gateway` 只传递 canonical message block、resolved external template ref、profile config、可选 `externalSecretRef`、idempotency key 和 trace context
7. remote provider 因 external template、canonical payload 或外部 channel 约束无法完成转换时，必须返回完整 `ExtensionError`；错误类别按 `extension-protocol.md §2.3` 映射，`details` 不得包含 secret 或原始 payload 明文

返回：

```json
{
  "status": "SENT",
  "externalMessageId": "msg-remote-1",
  "retryable": false,
  "metadata": {}
}
```

provider 只返回本次远端调用结果；delivery 状态权威仍在 `channel-gateway`。

Outbound delivery 当前不做定时任务重试，也不进入 provider job。一次 outbound delivery 只执行一次 `sendOutbound` 远端调用；失败后落库为 `FAILED`，同时写错误日志，由人工或上游业务重新发起。Core 不做自动升级、告警或补偿流程；不引入额外 `NEEDS_ATTENTION` 状态。

`channel_outbound_delivery` 字段：

```text
status              # PENDING / SENDING / SENT / FAILED
attempt_count
last_error
idempotency_key     # 对应 sendOutbound request envelope 的 idempotencyKey
```

规则：

1. `attempt_count` 记录本次 delivery 已发起的远端调用次数，当前目标实现通常为 0 或 1
2. `idempotency_key` 用于远端幂等，不表达本地重试计划
3. 不保存本地重试计划字段，也不引入重试排队状态
4. provider 返回 retryable error 时，`channel-gateway` 只记录错误属性，不自动安排下一次发送

## 6. Provider Jobs

Provider 级任务包括：

1. 拉取消息
2. 同步群 / 用户 / 组织
3. provider health check
4. webhook 补偿扫描

Provider job 不包含 credential refresh、credential rotate 或 credential status sync。当前 Core credential lifecycle 只覆盖 descriptor 主动声明 credential endpoints 后，由用户 / 管理员提交的固定常量密钥类型；如果某个 provider 需要 access token refresh、OAuth refresh token 或其他短期 token 轮换机制，必须在 extension 私有配置 / 私有存储内自行维护。`channel-gateway` 不触发这类刷新，也不通过 provider job 回写 `integration_account.external_secret_ref` 或 `credential_status`。

业务级定时任务归 worker / Temporal，不放进 channel provider。Outbound delivery 不进入 provider job，也不做定时重试。

当前调度权责：

1. `channel-gateway` 负责保存 provider job config
2. `channel-gateway` 使用应用内固定频率 schedule tick 扫描 due jobs
3. 实际 job execution 统一走 provider `runJob`
4. 多实例并发控制只使用 Redis lock，不引入其他调度协调层或 Temporal schedule
5. Temporal schedule 不承担 provider job 调度

Provider job 能力命名统一为 `runJob`：

1. manifest 使用 `jobDefinitions` 声明可配置 job 类型
2. `jobDefinitions` 非空时，gateway-native adapter 使用 `runJob(request)`，remote provider 使用 `POST {provider.endpoints.runJob}`
3. `jobDefinitions` 为空时，不声明 `endpoints.runJob`，Web / API 展示无 provider job 配置
4. 不再使用 `listScheduledJobs` / `runScheduledJob` 命名
5. job config 的权威保存位置是 `channel-gateway`
6. `jobConfig` 不得包含 secret；需要 token、API key、webhook signing secret 或私有系统凭证时，
   provider 必须通过 invocation envelope 中已有的 `externalSecretRef`、extension 私有配置或 extension 私有存储获取

调度实现：

```text
@Scheduled(fixedDelay = providerJobScanInterval)
channel-gateway scheduler tick
  -> reclaim stale RUNNING jobs
  -> scan due ACTIVE jobs
  -> acquire Redis lock per job
  -> claim job and create RUNNING run in one DB transaction
  -> call provider runJob
  -> persist run result / cursor / nextRunAt and move job back to ACTIVE
  -> if events returned, ingest as NormalizedChannelInboundEvent
```

Redis lock：

```text
lock key = channel-provider-job:{jobId}
lock value = runId
ttl = max(jobTimeoutSeconds * 2, 60s)
```

`jobTimeoutSeconds` 取自 `channel_profile_job.schedule_config.jobTimeoutSeconds`。创建 / 重置 job 时，
API / channel-gateway 用 `jobDefinitions[*].defaultJobTimeoutSeconds` 回填该字段；manifest 未声明时使用
`deployment-and-governance.md §9` 的 `channel runJob` 默认值 `60s`。channel-gateway 执行时不直接从当前 manifest
重算 timeout，TTL 在 lock 获取时使用已持久化的 schedule config 计算。
每次 claim 时必须把实际使用的 `jobTimeoutSeconds` 快照到 `channel_profile_job_run.job_timeout_seconds`，
后续完成写回和 stale recovery 都使用 run row 上的快照值，避免 manifest 变更影响已开始的 run。

规则：

1. 每个实例都可以运行同一个 `@Scheduled` tick
2. Redis lock 是唯一多实例去重机制
3. 拿不到 lock 的实例直接跳过该 job
4. 进程崩溃后依赖 lock TTL 释放，不做 leader 接管协议
5. job 是否 due 由 `channel_profile_job.status = ACTIVE`、`channel_profile_job.next_run_at` 与不含 `enabled` 的 `schedule_config` 计算；`scheduleType = MANUAL` 不进入自动扫描
6. `RUNNING` 是持久 job 状态，表示当前已有执行实例完成了 DB claim；不能只靠 Redis lock 或 run history 推导

DB claim 顺序：

1. scanner 先生成 run id，并用该 run id 作为 value 获取 `channel-provider-job:{jobId}` Redis lock
2. 在同一个 DB transaction 内锁定 / 条件更新 `channel_profile_job`
3. claim 条件必须包含 `status = ACTIVE`、`next_run_at <= now`、`schedule_config.scheduleType != MANUAL`
4. 生成 `idempotencyKey`，插入 `channel_profile_job_run(status = RUNNING, scheduled_at, started_at, job_timeout_seconds, idempotency_key, attempt)`
5. 更新 `channel_profile_job.status = RUNNING`、`last_run_id = runId`、`last_run_at = now`
6. DB claim 失败时释放 Redis lock 并跳过，不调用 provider

手动触发 run 使用同一套 claim 规则，但不要求 `next_run_at <= now`；仍必须满足
`status = ACTIVE` 且 Redis lock 可获取，`scheduleType = MANUAL` 的 job 也可手动触发。
`DISABLED` / `PAUSED` job 不允许手动触发，internal endpoint 返回 409
`CHANNEL_PROVIDER_JOB_NOT_ACTIVE`。job 已处于 `RUNNING` 或 lock 已被持有时，
internal endpoint 返回 409 `CHANNEL_PROVIDER_JOB_RUNNING`。

完成写回规则：

1. provider 调用必须以 `jobTimeoutSeconds` 作为硬超时
2. 成功时更新 run 为 `SUCCEEDED`，写入 `finished_at`、`duration_ms`、`events_ingested`、`next_cursor`，更新 job `cursor`、`last_success_at`、`next_run_at`，并把 job `status` 改回 `ACTIVE`；`scheduleType = MANUAL` 时 `next_run_at = null`
3. 失败或 provider 调用超时时更新 run 为 `FAILED` / `TIMED_OUT`，更新 job `failure_count`、`last_error`、`last_error_at`、`next_run_at`，并把 job `status` 改回 `ACTIVE`；`scheduleType = MANUAL` 时 `next_run_at = null`
4. 完成写回前必须确认当前实例仍持有 value 为 `runId` 的 Redis lock
5. 完成写回必须校验 `channel_profile_job.status = RUNNING`、`last_run_id = runId` 且 run 仍为 `RUNNING`
6. 如果 lock 已丢失，或 run 已被恢复流程标记为终态，本次迟到结果只写日志，不覆盖已恢复状态

崩溃恢复规则：

1. 每次 scheduler tick 先扫描 `status = RUNNING` 的 jobs
2. stale 条件必须同时满足：`last_run_id` 指向的 run 仍为 `RUNNING`、Redis lock 不存在、`now >= run.started_at + run.job_timeout_seconds`
3. 恢复流程在一个 DB transaction 内把 run 标记为 `TIMED_OUT`，写入 `finished_at`、`duration_ms` 和 timeout error
4. 同一 transaction 更新 job：`status = ACTIVE`、`failure_count += 1`、`last_error`、`last_error_at`、`next_run_at = next scheduled time`；`scheduleType = MANUAL` 时 `next_run_at = null`
5. stale recovery 不立即补跑；下一次执行仍由 `schedule_config` 计算出的 `next_run_at` 决定

失败策略：

```text
error:
  nextRunAt = next scheduled time, or null for MANUAL
  status = ACTIVE
  lastError = ...
```

Provider job 不做失败后的立即重试，也不做自动降级。失败只更新 `failure_count`、`last_error`、`last_error_at`，并写错误日志，下一次执行仍由原 schedule 决定。需要人工干预时由管理员手动 `PAUSED` job；Core 不引入 `NEEDS_ATTENTION` 自动转换或告警。

Provider job 状态事实源：

1. `channel_profile_job.status` 是运行态事实源
2. `scheduleConfig.enabled` 只存在于 create / update / reset 输入中；保存时先补齐缺省值，再投影成 `ACTIVE` 或 `DISABLED`
3. 持久化的 `channel_profile_job.schedule_config` 不保存 `enabled`
4. `enabled = true` 保存为 `status = ACTIVE`；如果 `scheduleType != MANUAL`，同时计算下一次 `next_run_at`，如果 `scheduleType = MANUAL` 则 `next_run_at = null`
5. `enabled = false` 保存为 `status = DISABLED`，并把 `next_run_at` 置为 `null`
6. `PAUSED` 只表示管理员暂停运行态，不由 `enabled=false` 推导；暂停期间 scanner 忽略 `next_run_at`，恢复时由管理员操作或再次保存 `enabled=true` 转回 `ACTIVE` 并重新计算 `next_run_at`
7. `RUNNING` 期间拒绝配置更新、启用、禁用和暂停；当前 run 完成或 stale recovery 后再按管理操作更新

Provider job 状态：

```text
ACTIVE          # 正常等待下一次调度
RUNNING         # 已被某个实例获取 Redis lock、完成 DB claim 并执行中
PAUSED          # 管理员暂停
DISABLED        # 配置禁用
```

失败字段：

```text
failure_count
last_error
last_error_at
last_run_id
last_success_at
next_run_at
```

remote provider endpoint：

```text
POST {provider.endpoints.runJob}
```

请求 envelope：

```json
{
  "providerType": "enterprise.acme.internal-im",
  "channelProfileId": "channel-profile-xxx",
  "config": {},
  "externalSecretRef": "vault://...",
  "idempotencyKey": "channel-job-run-xxx",
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  },
  "payload": {
    "jobType": "PULL_MESSAGES",
    "jobConfig": {},
    "scheduledAt": "2026-04-25T00:00:00Z"
  }
}
```

`externalSecretRef`、`idempotencyKey` 和 `traceContext` 的字段规则引用 `extension-protocol.md §2.0`。`externalSecretRef` 是可选字段，仅当 channel runtime profile 已固化 Core-managed credential ref 时发送。

返回：

```json
{
  "status": "SUCCEEDED",
  "nextCursor": "cursor-xxx",
  "events": [],
  "metadata": {}
}
```

`events` 使用同一个 `NormalizedChannelInboundEvent` DTO。

## 7. 统一错误模型

remote provider 必须使用 `extension-protocol.md §2.3` 的完整 `ExtensionError` schema。
Channel-specific 错误通过 `errorCode` 表达，不能裁剪 `ExtensionError` 字段。

所有 remote provider request 必须带 `idempotencyKey` 和 `traceContext.traceparent`，字段规则引用 `extension-protocol.md §2.0`。

## 8. 数据模型

```text
channel_profile
  id
  integration_account_id    # nullable; optional core-side Integration Account reference
  external_secret_ref       # nullable; optional Core-managed credential ref snapshot
  provider_type
  name
  status
  inbound_enabled
  config                  # jsonb not null; validated by configSchema
  assistant_binding       # jsonb not null; validated by platform assistant-binding.schema.json

channel_profile_template_binding
  id
  assistant_id
  channel_profile_id
  message_type            # e.g. CARD
  message_subtype         # CARD.cardType, e.g. ORDER_STATUS
  message_version         # CARD.version
  external_template_id
  external_template_version
  enabled
  variable_schema         # jsonb; optional validation schema for messageBlock.data
  display_name            # optional human-readable label shown in Web
  external_edit_url       # optional provider console URL for editing this external template

channel_profile_job
  id
  channel_profile_id
  job_type
  status                  # ACTIVE / RUNNING / PAUSED / DISABLED
  schedule_config         # jsonb not null; validated by schedule-config.schema.json
  cursor
  last_run_id             # points to current RUNNING run or latest finished run
  last_run_at             # current RUNNING run started_at or latest run started_at
  last_success_at
  last_error
  last_error_at
  next_run_at
  failure_count

channel_profile_job_run
  id
  job_id
  status                  # RUNNING / SUCCEEDED / FAILED / TIMED_OUT
  scheduled_at
  started_at
  job_timeout_seconds
  finished_at
  duration_ms
  idempotency_key
  attempt
  events_ingested
  next_cursor
  error
  metadata
```

`channel_profile` 是 channel runtime profile。明文 credential 和 credential status 不落在 `channel_profile`；profile 在启用 / 更新时可以固化已选择 Integration Account 的 `externalSecretRef`，供 outbound delivery 与 provider job 直接使用。未选择 account 或 account 没有 Core-managed credential 时，该 ref 为空。

Channel Profile 初始化 / 更新规则：

1. `channel_profile` 不由 manifest 自动生成；它由管理员通过 Web / API 创建，是某个 channel provider 的具体接入实例
2. 创建时管理员选择 `providerType`、可选 Integration Account、名称、inbound 开关、`assistant_binding` 和 provider-specific `config`
3. `config` 未提交时回填 manifest 中的 `defaultConfig`；`assistant_binding` 使用平台固定 schema，assistantId 不从 provider manifest 回填
4. `config` 和 `assistant_binding` 持久化为 `jsonb not null`，空配置使用 `{}`，不使用 SQL `null` 表达默认语义
5. 更新时 API / Web 可以提交完整对象；API 先校验已选择的 Integration Account 与 `assistant_binding` 引用，`channel-gateway` 再按 provider schema 和平台固定 schema 校验后整体替换
6. API 写入前必须校验 `assistant_binding.assistantId` 存在、未归档、可发布且当前操作者可见；启用 inbound 时该字段必填，禁用 inbound 时允许保留已有 binding 但不得用于事件路由
7. API 只在 create / update 边界校验 assistant / scenario 引用；保存后引用失效不由 `channel-gateway` 感知，后续对话或 control-plane 操作由 API 返回 assistant / scenario 不可用错误
8. profile 可以保存 `accountId + externalSecretRef`；二者来自管理员选择的 Integration Account 和当时已有的 Core-managed credential，不是所有 provider 的必填字段
9. `channel_profile` 是 profile aggregate root；template binding、provider job、outbound delivery 等子资源归属于该 profile，但不塞进 profile 主表 JSON

### 11.1 Channel Account -> Channel Profile Rename Scope

目标架构直接使用 `channel_profile`，不保留旧 `channel_account` 命名、API path、DTO 或兼容层。
实现时按以下清单一次性改完：

数据库 / jOOQ：

1. `channel_account` 表改为 `channel_profile`
2. `channel_conversation_binding.channel_account_id` 改为 `channel_profile_id`
3. `channel_inbound_event.channel_account_id` 改为 `channel_profile_id`
4. `channel_outbound_delivery.channel_account_id` 改为 `channel_profile_id`
5. index / unique constraint 名称同步改为 profile 语义：
   `idx_channel_profile_updated`、`idx_channel_binding_profile_updated`、
   `idx_channel_inbound_profile_created`、`idx_channel_outbound_profile_created`、
   `uk_channel_conversation_binding_profile_external_conversation`
6. `channel_profile_template_binding`、`channel_profile_job`、`channel_profile_job_run`
   从第一次落地就使用 profile 命名
7. 重新生成 `apps/channel-gateway/src/generated/jooq`，目标 generated code 不再有
   `ChannelAccount`、`ChannelAccountRecord` 或 `CHANNEL_ACCOUNT_ID`

API / DTO：

1. Web-facing path 固定为 `/api/channel-admin/profiles`
2. channel-gateway internal path 固定为 `/internal/channel-admin/profiles`
3. request / response DTO 命名使用 `ChannelProfile`、`CreateChannelProfilePayload`、
   `UpdateChannelProfilePayload`
4. path variable 使用 `channelProfileId`，不用 `accountId`
5. error code / message、audit event 和 metrics label 使用 `channel profile` 语义

代码 / 测试：

1. API `ChannelGatewayClient` 只调用 internal profiles path
2. `channel-gateway` repository / service / controller / gateway-native provider 代码全部使用 profile 命名
3. Web service、types、pages 和 tests 全部使用 `ChannelProfile`
4. Feishu 等 gateway-native provider 中按 provider-native identity 查找的是 profile，例如
   `findProfileByProviderAppId`
5. tests 必须验证旧 `/accounts` path 不存在；不得保留 redirect、alias 或兼容 DTO
6. `rg "ChannelAccount|channel_account|channel_account_id|/channel-admin/accounts"` 在目标源码、OpenAPI、
   generated jOOQ 和测试中不得有残留

当前仓库旧实现中的 `channel_account`、`ChannelAccount*`、`/channel-admin/accounts` 仅作为实现时需要删除 /
替换的来源，不是目标架构的一部分。

Template binding 规则：

1. `channel_profile_template_binding` 只保存 assistant 在某个 channel profile 上的 presentation 映射，不保存外部模板 body、卡片 JSON 或平台 DSL
2. binding 维度固定为 `assistant_id + channel_profile_id + message_type + message_subtype + message_version`
3. 同一维度最多允许一个 enabled binding
4. `message_subtype` 对 `CARD` message 使用 `CardMessageBlock.cardType`；`message_version` 使用 `CardMessageBlock.version`
5. `external_template_id` / `external_template_version` 是 provider-native opaque value，例如飞书卡片模板 ID；Core 不解析其内部格式
6. `variable_schema` 只用于校验 `messageBlock.data`，不得包含 secret，也不表达模板内容
7. `display_name` 和 `external_edit_url` 只用于 Web 展示和管理员跳转，不参与 outbound delivery 解析；二者由管理员维护，Core 不向 provider 拉取模板列表
8. binding 更新只影响后续 outbound delivery；已创建 delivery 必须保留当时解析出的 `resolvedTemplate`

`channel_profile_job.schedule_config` 固定为平台通用 schedule envelope：

```text
scheduleType       # INTERVAL / CRON / MANUAL
intervalSeconds
cronExpression
timezone
jobTimeoutSeconds
jobConfig          # validated by jobDefinitions[*].jobConfigSchema
```

`schedule_config` 持久化为 `jsonb not null`，且不包含 `enabled`。`scheduleType`、`timezone`、`jobTimeoutSeconds` 等平台通用字段由 `schedule-config.schema.json` 约束；`jobConfig` 由 provider manifest 中对应 `jobType` 的 `jobConfigSchema` 约束。创建 / 更新 job 的 write DTO 可以携带 `scheduleConfig.enabled`；`channel-gateway` 保存前先用 `defaultSchedule`、`defaultEnabled` 和 `defaultJobTimeoutSeconds` 补齐缺省值，再把 `enabled` 投影成 `channel_profile_job.status = ACTIVE / DISABLED`，最后只保存不含 `enabled` 的 `schedule_config`。`defaultJobTimeoutSeconds` 未声明时使用平台默认超时。

Integration Account 校验、`externalSecretRef` 固化和 credential status 规则引用 `credentials-and-persistence.md`。

`channel_profile_job` 保存 job 当前状态和下一次调度信息。`channel_profile_job_run` 保存每次执行历史，用于审计、排障、metrics 和执行历史分析。

`channel_profile_job` 约束：

```text
unique(channel_profile_id, job_type)
index(status, next_run_at)
index(status, last_run_at)
index(channel_profile_id, status)
```

`channel_profile_job_run` 目标 DDL 粒度：

```text
id                 varchar(64) primary key
job_id             varchar(64) not null references channel_profile_job(id)
status             varchar(32) not null -- RUNNING / SUCCEEDED / FAILED / TIMED_OUT
scheduled_at       timestamptz not null
started_at         timestamptz not null
job_timeout_seconds integer not null
finished_at        timestamptz
duration_ms        bigint
idempotency_key    varchar(128) not null
attempt            integer not null
events_ingested    integer not null default 0
next_cursor        text
error              jsonb not null default '{}'
metadata           jsonb not null default '{}'
created_at         timestamptz not null
updated_at         timestamptz not null
```

`channel_profile_job_run` 索引：

```text
unique(idempotency_key)
index(job_id, scheduled_at desc)
index(job_id, started_at desc)
index(status, started_at desc)
```

最终 SQL 以 `apps/channel-gateway/src/main/resources/db/migration/*` 为准；本文字段和索引用作 migration 的设计 checklist，不能只保留字段名而缺少类型和查询路径。

## 9. 迁移清单

`ChannelProviderType` string 化和相关影响面统一维护在 `implementation-roadmap.md` 的 impact checklist。
