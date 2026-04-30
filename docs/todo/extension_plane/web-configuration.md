# Web Schema-driven 配置

## 1. 目标

Web 不 import 企业 repo 代码，不硬编码企业 provider / connector 分支。

前端只消费 core 已注册的 extension definition。

## 2. Channel 配置页

前端从 API 读取：

```text
GET /api/extensions/channel-providers
```

目标契约权威是 `packages/contracts/openapi/control-plane.yaml`。落地时需在该文件新增：

```text
path: /extensions/channel-providers   # served under /api
response: ApiResponseChannelProviderDefinitionList
item schema: ChannelProviderDefinition
```

自动渲染：

1. Channel Profile config 表单，由 provider definition 的 `configSchema` 驱动
2. 可选 Integration Account 选择器
3. Provider job 配置表单
4. Assistant binding 配置，由平台固定 schema 驱动
5. External template binding 配置表格，用于维护 assistant 在当前 channel profile 上的外部模板 ID 映射

Channel Profile 页面同时渲染平台通用字段和 provider-specific 字段：

1. 平台通用字段：名称、状态、assistant / scenario binding、Integration Account、inbound 开关
2. provider-specific config：来自 `configSchema`，保存到 `channel_profile.config`
3. assistant binding：平台固定配置，保存到 `channel_profile.assistant_binding`

同一个 providerType 下允许创建多个 Channel Profile，并分别绑定不同 assistant / scenario。
Web 不把 assistant 映射写进 provider descriptor；assistant 路由属于 profile / binding 配置。

Channel Profile 不由 manifest 自动生成。管理员通过 Web / API 创建 profile，选择 provider、可选 Integration Account、assistant binding、启停状态和 provider-specific config；`channel-gateway` 负责校验后写入 channel runtime 表。

创建成功后，Web 必须展示可复制的 `channelProfileId`。对于 remote channel provider，Web / runbook 的首选配置路径是把 `channelProfileId` 放进 provider public webhook URL path，例如：

```text
https://{provider-public-host}/webhooks/lynxus/profiles/{channelProfileId}
```

`{provider-public-host}` 来自企业 extension 的部署 / runbook，不来自 registration `baseUrl`，Web 不暴露 extension internal URL。若外部系统不支持按对象配置独立 webhook URL，管理员才在 provider 私有配置 / 私有管理入口中维护 `external identity -> channelProfileId` 映射。Web 只展示 `channelProfileId`、profile 状态和配置指引，不定义 provider 私有映射管理协议。

第一版不在 Core / Web 保存 inbound verification status，也不定义 Core -> provider mapping API。
启用 inbound 不因外部 webhook 尚未验证而阻塞。Web 只展示 `channelProfileId`、推荐 webhook URL、
provider runbook 链接和“请用 test normalized event 验证外部映射”的提示；该提示不写入
`channel_profile`，不作为可查询状态，也不影响 profile enable / save。

验证闭环：

1. 管理员按 runbook 在外部系统或 provider 私有管理面配置 webhook / mapping
2. provider 发送 test normalized event，携带目标 `channelProfileId`
3. `channel-gateway` 校验 profile 存在、providerType 匹配、internal auth、assistant binding schema 和 dedup
4. 成功 / 失败结果进入 smoke check 或 runbook 输出，不回写 `channel_profile.inbound_verification_status`

Assistant binding 的引用校验由 control-plane API 负责，不由 Web 本地推断：

1. Web 的 assistant selector 必须使用 control-plane 暴露的 assistant / scenario 数据源，只展示当前操作者可见且可发布的 assistant
2. 提交创建 / 更新 Channel Profile 时，API 必须重新校验 `assistantId` 存在、未归档、可发布且当前操作者可见，不能信任 Web 下拉结果
3. 启用 inbound 时 `assistantId` 必填；禁用 inbound 时可以保存已有 binding 供后续启用，但不得被 `channel-gateway` 用于事件路由
4. 若 API 返回 assistant 不可用错误，Web 必须阻止保存或启用，并提示管理员重新选择 assistant / scenario
5. `scenarioId` 如填写，必须由 API 校验其属于当前 `assistantId` 且可用
6. Channel Profile 保存后，Web 不依赖 `channel-gateway` 感知 assistant / scenario 后续失效；后续对话或管理操作如果收到 API 返回的 assistant / scenario 不可用错误，Web 必须提示管理员重新选择或修复 binding

Assistant binding 固定字段：

```text
assistantId
scenarioId
bindingScope             # CONVERSATION / USER_IN_CONVERSATION / USER
customerIdentitySource   # EXTERNAL_USER_ID / EXTERNAL_CONVERSATION_ID / COMPOSITE_CONVERSATION_USER
sessionPolicy.mode       # REUSE_ACTIVE_OR_CREATE / ALWAYS_CREATE / REUSE_UNTIL_IDLE_TIMEOUT
sessionPolicy.idleTtlSeconds
```

External template binding 不编辑模板内容，只维护映射：

```text
assistantId
channelProfileId
messageType          # e.g. CARD
messageSubtype       # CARD.cardType
messageVersion       # CARD.version
externalTemplateId
externalTemplateVersion
enabled
variableSchema
displayName
externalEditUrl
```

Web 不理解 provider-native 模板 DSL，也不保存卡片 JSON。`externalTemplateId` 是目标 channel 的 opaque ID；`variableSchema` 可用于校验 `CardMessageBlock.data`，但不得包含 secret。`displayName` 和 `externalEditUrl` 只用于表格展示、详情页识别和跳转到 provider 控制台，由管理员维护；Core 不向 provider 拉取模板列表。

## 3. Tool Connector 配置页

前端从 API 读取：

```text
GET /api/extensions/tool-connectors
```

目标契约权威是 `packages/contracts/openapi/control-plane.yaml`。落地时需在该文件新增：

```text
path: /extensions/tool-connectors   # served under /api
response: ApiResponseToolConnectorDefinitionList
item schema: ToolConnectorDefinition
```

自动渲染：

1. connector 选择
2. account requirement
3. connector config
4. operation mapping
5. 可选 Integration Account 选择器

Definition endpoint 规则：

1. `/api/extensions/channel-providers` 和 `/api/extensions/tool-connectors` 是 Web 读取 extension definition 的统一入口
2. 这两个 endpoint 是 control-plane API，不属于 extension service 必须暴露的 endpoint，也不属于 Extension Plane invocation protocol
3. response DTO、字段名、nullable / required 规则以 `control-plane.yaml` 中的 `ChannelProviderDefinition` / `ToolConnectorDefinition` 为准
4. DTO 不暴露 extension `baseUrl`、remote endpoint path、internal token 或 runtime-only registry state
5. `/api/channel-admin/*` 只保留 Channel Profile / runtime admin 操作
6. `/api/catalog/*` 只保留 Tool Resource / catalog 业务对象操作
7. Web 不从 channel admin 或 catalog endpoint 推断 extension registry 内容
8. Definition DTO 是 API 对 manifest descriptor 的 control-plane projection，不是 manifest 原样透传
9. `definitionDigest` 可以暴露给 Web / 运维排障，但 Web 不用它做业务判定
10. Definition DTO 不提供 `accountRequirement = REQUIRED / OPTIONAL / NONE`；Integration Account 仍是可选引用，缺少 credential 是否可运行由 extension runtime 决定
11. `credentialCapability.supported = true` 只表示 Core / Web 可以管理 credential，不表示 runtime 必需 credential
12. `credentialCapability.mode` 表达管理模式：`REMOTE_LIFECYCLE` 表示调用 extension credential endpoints，`CORE_ENCRYPTED_REFERENCE` 表示沿用 API 本地加密存储和 internal resolver
13. endpoint capability 不暴露具体 path；非敏感能力用布尔或结构化字段表达，例如 `credentialCapability.supported` / `mode`；`sendOutbound` 是所有 channel provider 的固定能力，不需要展示开关，provider job 能力由 `jobDefinitions` 是否为空自然表达

`ChannelProviderDefinition` 目标形态：

```json
{
  "providerType": "enterprise.acme.internal-im",
  "title": "Acme Internal IM",
  "description": "Internal messaging provider",
  "definitionDigest": "sha256:...",
  "accountConfigSchema": {},
  "accountConfigUiSchema": [],
  "credentialCapability": {
    "supported": true,
    "mode": "REMOTE_LIFECYCLE",
    "credentialSchema": {},
    "credentialUiSchema": []
  },
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
      "defaultEnabled": false
    }
  ]
}
```

`ToolConnectorDefinition` 目标形态：

```json
{
  "connectorType": "enterprise.acme.crm",
  "title": "Acme CRM",
  "description": "CRM connector",
  "definitionDigest": "sha256:...",
  "accountConfigSchema": {},
  "accountConfigUiSchema": [],
  "credentialCapability": {
    "supported": true,
    "mode": "REMOTE_LIFECYCLE",
    "credentialSchema": {},
    "credentialUiSchema": []
  },
  "configSchema": {},
  "configUiSchema": [],
  "operationMappingSchema": {},
  "operationMappingUiSchema": []
}
```

字段规则：

1. `providerType` / `connectorType` 是业务 descriptor id，来自 manifest descriptor
2. Channel Provider 和 Tool Connector 都使用 `title` 作为必填展示名；`description` 可选
3. `credentialCapability.supported = false` 时，`mode`、`credentialSchema` 和 `credentialUiSchema` 必须为 `null`、缺省或空数组
4. `credentialCapability.mode = REMOTE_LIFECYCLE` 时，API 调用 extension credential endpoints；`mode = CORE_ENCRYPTED_REFERENCE` 时，API 本地加密保存 credential，不暴露 remote credential lifecycle
5. schema / UI schema 字段不得包含 secret material、`externalSecretRef` 或可还原 secret 的默认值
6. UI schema 只表达展示 hint；数据校验仍以对应 JSON Schema 为准
7. API 在 projection 时必须过滤掉 endpoint path、registrationId、baseUrl、auth 配置和 runtime-only registry state
8. `accountConfigSchema` 始终属于 Integration Account；`configSchema` 始终属于 Channel Profile 或 Tool Resource 的运行配置，二者不得合并
9. Tool connector definition DTO 固定由 schema、UI schema 和 credential capability projection 组成；Tool Resource 的 config 和 operation mapping 必须由保存态显式提供
10. Channel Provider `defaultConfig` 必须已经通过对应 `configSchema` 校验

## 4. UI 边界

1. Web 不允许用户填写 extension baseUrl
2. Web 只展示 core registry 中已注册的 provider / connector
3. Credential 表单在统一 Integration Account 管理入口中渲染；remote descriptor 只有声明 credential endpoints 时展示，core reference descriptor 使用 Core-owned encrypted reference secret 时也可以展示
4. Channel profile 和 Tool connector config 可以选择 `integration_account.id`，但 account 不是 descriptor 的默认强制要求
5. Credential 表单可以由 extension definition 的 `credentialSchema` 描述
6. 创建 Integration Account 时可以在同一个 Web-facing request 中提交 credential；API 先生成 `integration_account.id`，再按 descriptor 模式调用 extension credential create endpoint 或写入 Core-owned encrypted reference secret
7. enterprise remote descriptor 未声明 credential endpoints 时不提供 Core/Web credential 配置入口，只允许创建非敏感 `config`
8. Web 不回显真实 credential

## 5. UI Field Schema

UI field schema 属于 `packages/extension-protocol/json-schema/ui-field.schema.json`，用于在 JSON Schema 之外提供展示 hints。数据校验权威仍是对应 JSON Schema。Manifest 中 UI schema 是对应数据 schema 的 sibling 字段；Definition DTO 只暴露 API projection 后的同名字段，例如 `accountConfigUiSchema`、`credentialUiSchema`、`configUiSchema`、`operationMappingUiSchema`、`configUiSchema`、`jobConfigUiSchema`。

UI schema 的值是 UI field array；空数组表示没有额外展示 hint。UI field `key` 必须是 RFC 6901 JSON Pointer，并指向对应 JSON Schema 中存在的 object property：

```json
[
  {
    "key": "/tenantId",
    "label": "Tenant",
    "component": "text",
    "order": 10
  }
]
```

基础字段：

```text
key
label
description
placeholder
component
required
defaultValue
options
visibilityCondition
validationMessage
secret
readOnly
order
group
```

组件类型：

```text
text
textarea
password
number
boolean
select
multiSelect
radio
checkboxGroup
json
cron        # value 为 5 段标准 cron 表达式（minute hour dayOfMonth month dayOfWeek），按 channel_profile_job.schedule_config.timezone 解析
duration    # value 为 ISO-8601 duration 字符串，例如 "PT1H30M"、"PT15S"
url
email
dateTime    # value 为 RFC 3339 / ISO-8601 时间字符串，例如 "2026-04-25T00:00:00Z"
```

规则：

1. Web 根据 JSON Schema 做数据校验，根据 UI field schema 选择控件和布局
2. JSON Schema 是数据契约权威：`required` / `enum` / 类型约束和 validation keyword 以 JSON Schema 为准
3. UI field schema 中的 `required` / `defaultValue` 只是展示 hint；`required` 不得与 JSON Schema 冲突，`defaultValue` 只用于表单初始展示，不参与 definition digest
4. 未识别的 `component` 使用 JSON editor fallback
5. `secret=true` 字段只允许用于 credential 表单，只允许输入，不允许回显
6. UI field schema 不允许携带 credential 明文或默认 secret
7. 普通 user-editable config 表单不得使用 secret 控件，禁用范围以
   `extension-protocol.md §2.0 User-editable Config Secret Boundary` 为唯一权威
8. visibility condition 只能引用当前表单内字段，字段引用使用 RFC 6901 JSON Pointer，不允许执行脚本
9. `visibilityCondition` 只控制 UI 显隐，不改变数据契约
10. conditional required、条件类型、互斥字段和模式分支必须用 JSON Schema 表达，不能只写在 UI field schema
11. 推荐使用 JSON Schema `if` / `then` / `else` 表达条件必填，使用 `oneOf` 表达互斥模式分支，
    使用 `dependentRequired` 表达“有 A 就必须有 B”
12. Web 提交 payload 必须满足后端 JSON Schema；隐藏字段里的空值或未触碰值可以省略
13. 如果字段被 UI 隐藏但 JSON Schema 当前分支仍然 required，视为 schema 作者错误，manifest validation /
    contract test 必须失败
14. Web 不实现 dynamic options endpoint；`select` / `radio` / `checkboxGroup` 只消费 manifest 中已通过校验的静态 enum / const 选项

`select` / `radio` / `checkboxGroup` 的选项来源只允许来自当前 JSON Schema 的静态枚举：`enum`、`oneOf[*].const` / `oneOf[*].title` 或 UI field schema 中与 JSON Schema enum 完全一致的 label hint。当前 manifest schema 不提供 dynamic options endpoint，也不允许 Web 在渲染配置表单时调用 extension 获取选项；需要动态选项时必须先扩展 control-plane definition API 和 manifest schema，不能在 UI schema 中塞 URL 或脚本。

Web renderer 第一版采用 `apps/web` 内自研的薄 `SchemaDrivenForm`，用 Ant Design Vue 控件渲染
UI field schema，用 Ajv 2020 校验对应 JSON Schema。不引入重型 JSON Schema form generator，也不让
renderer 解释 extension 私有 DSL。原因是当前 UI field schema 已经把控件集合、分组、顺序、静态选项和显隐条件限制在很小范围；自研薄层更容易保证 secret 控件、隐藏字段清理、Ant Design Vue 交互和 API validation error 映射都符合平台约束。

渲染器职责：

1. 根据 UI field `key` 从 JSON Schema 解析字段类型、enum 和 required 状态
2. 根据 `component` 选择 Ant Design Vue 控件；未知 component 使用 JSON editor fallback
3. 根据结构化 `visibilityCondition` 控制显隐，不改写 JSON Schema
4. 提交前使用 Ajv 2020 对完整表单值做 JSON Schema validation
5. 把 API 返回的字段级 validation errors 映射回 JSON Pointer 对应控件
6. 不生成默认 secret，不回显 secret，不从 hidden 字段补值满足 required

## 6. Credential 编辑态交互

Credential endpoint 协议引用 `extension-protocol.md §2.2`；credential status 规则引用
`credentials-and-persistence.md §6.1` / `§8`。第一版不展示 credential metadata。

remote descriptor 只有声明 credential endpoints 时，Web 才展示 credential create / validate / rotate /
revoke 操作。core reference descriptor 使用 Core-owned encrypted reference secret 时，Web 展示 create /
rotate / revoke，但 validate 只能做本地 schema / 解密可用性检查，不调用 extension validate endpoint。
其他未声明 credential endpoints 的 enterprise remote descriptor 只展示 Integration Account 的非敏感 config 和
`credential_status = NOT_CONFIGURED`，并提示 credential 由 extension 私有配置或私有管理面维护。

Credential 编辑态只允许动作式操作：

1. validate
2. rotate
3. revoke
4. 为已有未配置成功的 account create credential
5. 创建新的 Integration Account 并可同步提交 initial credential

Web 不提供 credential metadata 编辑或展示区域；credential lifecycle response 不回填账号展示信息。

动作展示规则：

1. `创建新的 Integration Account` 表单在 descriptor 声明 credential endpoints 时可以同屏展示初始 credential 表单；提交后由 API 一步创建 account 并调用 extension `createCredential`
2. `create credential` 只用于已存在且尚未配置成功的 account：descriptor 声明 credential endpoints、account `externalSecretRef` 为空，且 `credential_status` 为 `NOT_CONFIGURED` / `VALIDATION_FAILED`
3. credential lifecycle 操作执行中不依赖持久 pending 状态；Web 禁用当前页面上的 credential lifecycle 动作并等待本次请求返回
4. 如果 API 返回 account 级短时排他锁冲突，Web 展示“已有凭证操作进行中，请稍后重试”，用户稍后手动再次提交
5. 操作失败后，用户可以手动再次提交新的 create / rotate / revoke / validate 请求；Web 不复用上一条请求的 `Idempotency-Key`
6. 一旦 account 已有 `externalSecretRef`，Web 不再展示 create；替换固定常量密钥只允许走 `rotate`
7. `REVOKED` 不展示 reset / create again；只展示 `创建新的 Integration Account`
8. `REVOKE_FAILED` / `REVOKED` 的 account 不允许新建绑定或生成新 runtime snapshot，Web 必须在选择器和保存动作上阻止
9. 已存在 release snapshot / channel profile 不由 Web 自动回收；运行时失败由 extension 边界暴露

允许普通编辑：

1. `integration_account.name`
2. 非敏感 `integration_account.config`

禁止编辑：

1. `externalSecretRef`
2. `credential_status`
3. 任意 secret material

## 7. Provider Job 配置表单

Provider job 表单由 provider definition 中的 `jobDefinitions` 驱动；`jobDefinitions` 缺失或为空时，Web 展示无 provider job 配置，不渲染 job 表单。

每个 job definition 字段包含：

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

`defaultEnabled` 只作为创建 / 重置 provider job 时的输入默认值；保存后展示和运行判断以
`channel_profile_job.status` 为准。`defaultJobTimeoutSeconds` 可省略；缺省时 provider job 使用平台默认超时。

Web 表单输出进入 provider job write DTO，API 转发给 `channel-gateway` 保存；`channel-gateway`
会把 `enabled` 投影成 `channel_profile_job.status = ACTIVE / DISABLED`，持久化的
`channel_profile_job.schedule_config` 不包含 `enabled`。Web 不直接调用 provider `runJob`。

调度配置写入字段：

```text
enabled            # write-only input; projected to channel_profile_job.status
scheduleType       # INTERVAL / CRON / MANUAL
intervalSeconds
cronExpression
timezone           # IANA tz database name, e.g. "Asia/Shanghai"; default "UTC"
jobTimeoutSeconds
jobConfig
```

规则：

1. `channel-gateway` 是 provider job config 权威保存方
2. Web 只能配置已注册 provider definition 暴露的 job type
3. 手动触发 job 走 channel-gateway admin action，再由 gateway 调 provider `runJob`
4. `cronExpression` 按 `timezone` 解析；DST 切换时段表达式触发行为遵循对应时区规则
5. `intervalSeconds` 不受 `timezone` 影响
6. `enabled`、`scheduleType`、`timezone`、`jobTimeoutSeconds` 等平台写入字段按 provider job write DTO schema 校验
7. `jobConfig` 按对应 `jobDefinitions[*].jobConfigSchema` 校验
8. Web 读取 job 时使用 `status` 展示启用、禁用、暂停和运行中；不得从 `schedule_config.enabled` 推导，因为该字段不会持久化
9. `scheduleType = MANUAL` 的 job 不自动调度；只有 `status = ACTIVE` 时允许手动触发

手动触发链路：

```text
Web
  -> POST /api/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
  -> API authorization + audit
  -> POST {channelGatewayBaseUrl}/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
  -> channel-gateway acquires Redis lock and claims job as RUNNING
  -> channel-gateway creates channel_profile_job_run
  -> provider runJob
```

API 只做鉴权、审计、assistant / account 引用校验和 DTO 映射，不直读 channel runtime 表；`channel-gateway` 是 profile、template binding、job config、job run 创建、Redis lock、状态推进和结果持久化 owner。OpenAPI 落地时必须在 `packages/contracts/openapi/control-plane.yaml` 覆盖 Web-facing endpoint，并在 `packages/contracts/openapi/channel-gateway-internal.yaml` 覆盖 internal endpoint。

手动触发 run 复用 provider job 的 `RUNNING` 状态机；只有 `status = ACTIVE` 的 job 可以手动触发。job 已在运行或 Redis lock 已被持有时，Web-facing API 返回 409 `CHANNEL_PROVIDER_JOB_RUNNING`；job 已禁用或已暂停时返回 409 `CHANNEL_PROVIDER_JOB_NOT_ACTIVE`，不创建新的 run。

手动触发的 `idempotencyKey` 由 `channel-gateway` 生成（建议形态：`channel-job-run-{channelProfileId}-{jobType}-{runId}`），写入 `channel_profile_job_run.idempotency_key`，并按 `extension-protocol.md §2.0` 透传给 remote provider，与 `@Scheduled` 调度链路共享同一字段语义。

Channel admin Web-facing DTO 与 gateway internal DTO 分离：

1. Web-facing DTO 可以返回 Integration Account 展示状态和 credential 风险提示
2. Web-facing DTO 不返回 `externalSecretRef` 原文
3. API 调 `channel-gateway` internal profile create / update 时，按 `credentials-and-persistence.md` 从 Integration Account 物化可选 `accountSnapshot.accountId` 和可选 `accountSnapshot.externalSecretRef`
4. API 调 internal endpoint 失败时，把 gateway error code 映射为 control-plane API error，不透出 internal URL、token、`externalSecretRef` 或 gateway 原始异常明文

Web-facing Channel Admin API 的路径、DTO 和映射规则以 `channel-provider.md §3.3` 为准，并落地到
`packages/contracts/openapi/control-plane.yaml`。Web 只调用 `/api/channel-admin/*`，不直接调用
`channel-gateway` 的 `/internal/channel-admin/*`。

## 8. Operation Mapping 表单

Tool operation mapping 表单由 connector definition 中的 `operationMappingSchema` 和 UI field schema 驱动。

规则：

1. Web 不硬编码 connector-specific operation mapping
2. operation mapping 保存到 Tool Resource / Tool Operation config
3. remote invocation 时由 agent-runtime 放入 `RemoteToolInvokeRequest.config.operationMapping`
4. 表单校验使用 JSON Schema
5. 控件和分组使用 UI field schema
6. 复杂对象允许 JSON editor fallback
7. operation mapping 由用户或 Tool Resource 创建流程显式保存；每个 operation 发布前必须有保存态 mapping

## 9. Integration Account 页面关系

统一 Integration Account 管理页是 Core-managed credential create / rotate / revoke / validate 的唯一入口。enterprise remote descriptor 未声明 credential endpoints 时不提供这些动作；core reference descriptor 使用 Core-owned encrypted reference secret 时，动作由 API 本地加密存储实现。

Integration Account 的非敏感 `config` 表单来自 descriptor 的 `accountConfigSchema`。Web 可以用它渲染表单并做提交前预校验，但最终校验 owner 是 control-plane API：

1. 创建 Integration Account 时，Web 必须提交 `subjectType`、`subjectId`、`name`、`config` 和可选 `credential`
2. 更新 Integration Account 时，Web 只提交 `name`、`config` 或 status 变更；固定常量密钥替换必须走 credential `rotate`
3. API 按 `subjectType + subjectId` 从当前 definition registry 查找 descriptor，并用该 descriptor 的 `accountConfigSchema` 校验 `config`
4. 若 create request 携带 `credential`，API 必须先生成 `integration_account.id`，再按 descriptor `credentialSchema` 校验 credential；remote credential lifecycle 模式调用 extension `createCredential`，Core-owned encrypted reference secret 模式直接加密写入 `integration_account.credential_ciphertext`
5. descriptor 不存在、registry validation 未完成或 registration / manifest drift 时，API 返回错误给 Web；Web 不本地兜底创建
6. `accountConfigSchema` 或 `credentialSchema` 校验失败时，API 返回字段级 validation errors；Web 展示错误并允许用户修改后重新提交
7. enterprise remote descriptor 未声明 credential endpoints 时仍允许创建只有非敏感 `config` 的 Integration Account，但 Web 不展示 credential lifecycle 动作，也不得提交 `credential`

Channel / Tool 配置页按 descriptor 规则引用 account：

```text
Channel Profile
  -> select integration_account.id where subject_type = CHANNEL_PROVIDER and subject_id = providerType

Tool Resource
  -> select integration_account.id where subject_type = TOOL_CONNECTOR and subject_id = connectorType
```

交互规则：

1. Channel Profile / Tool Resource 页面可以跳转创建 Integration Account
2. 当前配置页展示 account selector 时，创建完成后回填 `integration_account.id`
3. 若 `integration_account.status` 不是 `ENABLED`，配置页必须提示并阻止保存、发布或启用
4. `credential_status = REVOKE_FAILED / REVOKED` 时，配置页必须阻止新建绑定、保存会生成 runtime snapshot 的配置、发布或启用
5. `credential_status = NOT_CONFIGURED / VALIDATION_FAILED / ROTATION_REQUIRED` 只作为风险提示，不阻止保存、发布或启用；运行时失败由 extension 暴露
6. Credential 状态和校验动作在 Integration Account 页面展示；第一版不展示 credential metadata
7. Channel / Tool 页面不展示 credential 表单和 secret 输入
