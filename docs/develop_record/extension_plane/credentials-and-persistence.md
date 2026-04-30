# Credentials 与私有持久化

## 1. 目标边界

企业 extension 可以有独立持久化层，但它只保存 extension 私有状态，不保存 core 权威业务事实。

Core 仍持有：

1. session
2. playbook run
3. assistant release
4. catalog resource / tool resource
5. integration account 主身份、凭证 ref / core-owned encrypted reference credential 和账号状态
6. channel runtime profile
7. runtime event / platform event

企业 extension 可以持有：

1. extension 内部派生或刷新得到的 provider access token / refresh token
2. webhook 游标、轮询 checkpoint
3. 企业系统映射表
4. 私有幂等记录
5. 私有同步状态
6. extension 自己的审计日志
7. 企业内部系统访问缓存

## 2. Credential Storage Modes

### 2.1 Extension-managed Secret

```text
Web
  -> Core API create Integration Account with optional credential
    -> Core generates integration_account.id
      -> extension credential create endpoint(account config, credential)
        -> Enterprise Vault / KMS / extension private DB
          -> returns externalSecretRef
            -> Core API stores externalSecretRef only
```

上图只描述 extension 选择暴露 credential endpoints 时的 Core-managed credential flow。
Credential endpoints 是可选能力，不是 descriptor 必须能力。Extension 也可以通过自己的配置文件、
私有管理面、Vault / KMS、私有 DB 或部署系统维护 credential；这种模式下 Core 不创建、不轮换、
不校验 credential，也不会拥有 `externalSecretRef`。

### 2.2 Core-owned Encrypted Reference Secret

内置 reference connector / gateway-native provider 没有 extension 私有持久化层时，沿用当前实现：
Core API 在 `integration_account` 内保存加密后的固定常量 credential，并由内部 runtime credential
resolver 按 `accountId` 取回明文供 core in-process runtime 使用。

```text
Web
  -> Core API create / update Integration Account with credential
    -> Core encrypts credential JSON with deployment encryption key
      -> integration_account.credential_ciphertext / credential_fingerprint

agent-runtime / channel-gateway
  -> internal runtime credential resolver(accountId)
    -> Core API decrypts credential_ciphertext
      -> returns plaintext credential only over internal trusted call
        -> core reference connector / gateway-native provider uses credential in-process
```

规则：

1. 该路径只服务 core runtime service 暴露的 built-in reference descriptor，不属于 remote extension HTTP protocol
2. reference descriptor 不声明 credential endpoints；API 不调用 `createCredential` / `rotateCredential` / `revokeCredential` / `validateCredential`
3. reference descriptor 可以声明 `credentialSchema` / `credentialUiSchema` 作为 Web 表单和 API 校验契约；credential material 由 Core API 加密保存
4. `integration_account.external_secret_ref` 在该模式下保持 `null`；release snapshot / channel runtime profile 只固化 `accountId`
5. runtime owner 只允许通过 internal auth 调用 resolver；remote enterprise extension service 不允许调用该 resolver
6. 明文 credential 只允许存在于 Web -> API request、API 加密 / 解密瞬时内存、internal resolver response 和 core in-process adapter 调用栈；不得进入普通日志、metrics、runtime event、platform event、审计 diff、导出文件或 release snapshot
7. `credential_fingerprint` 只用于变更检测 / 审计去重，不可用于恢复 credential，也不得向 Web 展示原始值
8. create / rotate 本地加密成功后，`credential_status` 置为 `ACTIVE`；未提交 credential 时保持 `NOT_CONFIGURED`
9. validate 只检查 ciphertext 可解密、解密结果仍满足当前 descriptor `credentialSchema`；不调用外部系统做语义校验
10. revoke 清空或失效 `credential_ciphertext` / `credential_fingerprint`，并把 `credential_status` 置为 `REVOKED`

运行时：

```text
agent-runtime / channel-gateway
  -> extension invoke(providerType / connectorType, optional externalSecretRef, config, payload)
    -> extension loads secret internally
      -> calls enterprise system
```

Core credential lifecycle 或 Core-owned encrypted reference secret 只覆盖固定常量密钥类型，例如 API key、静态 bearer token、webhook signing secret、business code secret 或其他不会由 Core 自动刷新的常量 secret。Core 不建模 access token refresh、OAuth refresh token 或短期 token 轮换流程。

Core 不长期保存企业密钥明文，不把密钥写入 session、Temporal history、runtime event、platform event 或普通日志。只有 Core-owned encrypted reference secret 模式允许 Core 长期保存加密 ciphertext / fingerprint；remote extension-managed secret 不允许 Core 保存明文或 ciphertext。

普通业务配置也不得承载 secret。`channel_profile.config`、`channel_profile.assistant_binding`、
`channel_profile_template_binding.variable_schema`、`channel_profile_template_binding.display_name`、
`channel_profile_template_binding.external_edit_url`、
`channel_profile_job.schedule_config.jobConfig`、
Tool Resource connector config、operation mapping、assistant release snapshot、审计 diff 和导出文件
只保存非敏感业务配置或不可还原 secret 的引用。需要 secret 的场景必须通过 extension 私有配置 /
私有存储、Core-owned encrypted reference secret，或通过 release snapshot / channel runtime profile 中已有的 `externalSecretRef` 访问；
普通配置不得保存明文 secret。

API 只在 descriptor 声明 credential endpoints 时作为 credential lifecycle invocation owner。
credential create / rotate / revoke / validate 由 API 根据 Web / admin action 调用 extension
credential endpoints，并负责写回 `integration_account.external_secret_ref` 和
`credential_status`。descriptor 不声明 credential endpoints 时，API 不提供该 descriptor
的 remote credential lifecycle action；若 descriptor 来自 core runtime service 且采用 Core-owned
encrypted reference secret，则 credential create / update / rotate / revoke 都是 API 本地加密存储操作，
不调用 extension credential endpoint，也不写入 `external_secret_ref`。

remote credential lifecycle 中，`rotate` 的语义是用户 / 管理员提交新的固定常量密钥，并由 extension 在同一个 `externalSecretRef`
背后更新 credential material。`externalSecretRef` 是稳定引用，rotate 不替换该 ref，也不是 token refresh。若 extension 需要 refresh 机制，refresh 完全属于 extension 私有实现：extension 可以在自己的 Vault / KMS / 私有 DB 内刷新、缓存和替换短期 token，但不得要求 `agent-runtime` / `channel-gateway` 触发 refresh，也不得通过 provider job 直接变更 Core 的 `external_secret_ref` 或 `credential_status`。
Core-owned encrypted reference secret 中，rotate / update 只替换 `credential_ciphertext` 和 `credential_fingerprint`，仍不生成 `externalSecretRef`。

`agent-runtime` / `channel-gateway` 不负责创建或变更 credential。remote runtime invocation 不向 API 拉取任何 credential 数据或账号状态；它只消费发布 / 配置快照中已经固化的可选 `externalSecretRef`，并把该字段透传给 extension。Core-owned encrypted reference secret 的 in-process adapter 例外：runtime owner 可以按 snapshot 中的 `accountId` 调 internal resolver 获取明文 credential。

Integration Account 与 `externalSecretRef` 都不是 descriptor 的默认强制要求。是否选择 Integration Account、是否通过 Core 配置 credential、以及 extension 是否要求 credential 存在，均不在 registry 阶段或发布阶段强制校验；缺失导致的业务不可用由 runtime invocation 在 extension 边界暴露。

### 2.3 Runtime Secret Reference 传递

```text
Core API publish / channel profile update
  -> if Integration Account is selected, validates account subject and account status
  -> snapshots accountId and current externalSecretRef if present

remote runtime invocation
  -> reads optional externalSecretRef from local runtime snapshot
  -> passes only externalSecretRef to extension invocation

core in-process reference invocation
  -> reads accountId from local runtime snapshot
  -> uses internal credential resolver(accountId)
```

规则：

1. 未选择 Integration Account 时，API 不写入 Tool release `accountSnapshot` 或 channel runtime profile `accountId`，remote runtime request 不携带 `externalSecretRef`
2. 已选择 Integration Account 时，API 必须在写入 release snapshot 或 channel runtime profile 前校验 `subjectType` / `subjectId` 与目标 descriptor 匹配，并校验 `status = ENABLED`
3. API 不要求 `credential_status = ACTIVE` 才能生成 runtime snapshot；即使 descriptor 声明 credential endpoints，管理员也可以选择暂不通过 Core 配置 credential
4. API 只在 `integration_account.external_secret_ref` 当前有值时把 `externalSecretRef` 固化到 release snapshot 或 channel runtime profile
5. runtime 不重新实现 credential 状态机，不在执行前回 API 查询 account 状态或 credential 状态
6. remote runtime request 只传 snapshot 中已有且本次 invocation 需要的 `externalSecretRef`；不传 Core 内部 `accountId`
7. Core 内部 `accountId` 只允许进入 release snapshot、channel runtime profile、channel admin internal DTO 和 internal credential resolver，不进入 remote extension invocation envelope
8. `externalSecretRef` 允许进入 release snapshot、channel runtime profile 和 extension invocation envelope
9. `externalSecretRef` 不允许进入普通日志、metrics、runtime event、platform event、审计 diff 或导出文件
10. credential rotate 保持同一个 `externalSecretRef`，已发布 release snapshot 通常不需要更新；如果 snapshot 没有该 ref，则不会因 rotate 自动获得 ref
11. credential revoke 后 extension 必须使该 `externalSecretRef` 不再可解析；持有旧 snapshot 的 runtime invocation 会在 extension 边界失败
12. 内置 / reference connector 或 provider 没有独立 extension 私有存储时，使用 Core-owned encrypted reference secret 和 internal credential resolver 获取明文 credential；该路径不是 remote extension protocol 的一部分，不产生 `externalSecretRef`

## 3. 统一 Integration Account

Channel 和 Tool 共用统一 Integration Account。它是可选的 core-side 账号 / 凭证引用，承载账号主身份、可选密钥 ref、账号状态和 extension 绑定。

`integration_account.subject_type` 与 runtime invocation 协议中的 `descriptorType` 共享同一个枚举集合：`TOOL_CONNECTOR` / `CHANNEL_PROVIDER`。两者语义分别属于持久化 subject 与 invocation descriptor，但新增或删除枚举值必须作为同一处协议变更处理。

目标模型：

```text
integration_account
  id                        varchar(64) primary key
  subject_type              varchar(32)  not null  # TOOL_CONNECTOR / CHANNEL_PROVIDER
  subject_id                varchar(128) not null  # connectorType / providerType
  name                      varchar(128) not null
  status                    varchar(32)  not null
  config                    jsonb        not null default '{}'
  external_secret_ref       varchar(512)           # length cap follows extension-protocol.md §2.0
  credential_ciphertext     text                   # Core-owned encrypted reference secret only
  credential_fingerprint    varchar(128)           # Core-owned encrypted reference secret only
  credential_status         varchar(32)  not null
  metadata                  jsonb        not null default '{}'
  created_at                timestamptz  not null
  updated_at                timestamptz  not null
```

`external_secret_ref` 的 `varchar(512)` 上限与 `extension-protocol.md §2.0` 协议长度上限一致；
release snapshot 中固化的 `externalSecretRef`、channel runtime profile 中保存的
`externalSecretRef` 列宽度也按此上限对齐。`credential_ciphertext` / `credential_fingerprint`
沿用现有 Core API 加密存储实现，只服务 core-owned encrypted reference secret。

引用关系：

```text
Tool Resource connector config
  accountId -> integration_account.id

Tool release snapshot / Channel runtime profile
  accountId -> integration_account.id
```

`externalSecretRef` 的权威值落在 `integration_account`；发布后的 runtime snapshot 可以保存该 ref。没有通过 Core 配置 credential 的 account 可以保持 `external_secret_ref = null`。
Core-owned encrypted reference secret 的权威值也落在 `integration_account`，但只保存 ciphertext / fingerprint，不生成 `externalSecretRef`。

### 3.1 Integration Account Config 校验 owner

Integration Account 是 API-owned 对象，`integration_account.config` 的最终校验 owner 是
control-plane API。Web 可以用 definition DTO 中的 `accountConfigSchema` 做前端预校验和表单渲染，
但 Web 校验只用于体验优化，不能替代 API 校验。

create / update Integration Account 的校验规则：

1. `subject_type + subject_id` 是 account 绑定 descriptor 的唯一 selector：
   `TOOL_CONNECTOR + connectorType` 或 `CHANNEL_PROVIDER + providerType`
2. API 必须用当前 definition registry 查找 descriptor，并使用该 descriptor 的
   `accountConfigSchema` 校验请求中的 `config`
3. `config` 必须是 JSON object；是否允许额外字段、字段 required 和条件规则完全由
   `accountConfigSchema` 表达
4. descriptor 不存在、当前 registration 与 manifest registry 不一致，或服务启动阶段 registry validation 未完成时，
   API 直接向 Web 返回错误，不创建 / 更新 Integration Account
5. `accountConfigSchema` 只能描述非敏感 account config；secret material 只能来自 credential
   endpoints、Core-owned encrypted reference secret、extension 私有配置或 extension 私有存储
6. enterprise remote descriptor 未声明 credential endpoints 时，仍允许创建只有非敏感 `config`、没有
   `external_secret_ref` 的 Integration Account；API 不暴露 remote create / rotate / revoke / validate
   credential lifecycle action。core reference descriptor 使用 Core-owned encrypted reference secret 时，
   API 可以接收 credential 并本地加密保存
7. schema 通过不代表外部系统账号可用；租户是否存在、scope 是否真实、extension 私有 credential
   是否已配置等语义错误，由发布 / profile 更新或 runtime invocation 阶段暴露

错误映射：

| 场景 | HTTP | code |
| --- | --- | --- |
| `subject_type + subject_id` 找不到 descriptor | 422 | `INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND` |
| definition registry 启动校验未完成或检测到 registration / manifest drift | 503 | `EXTENSION_REGISTRY_NOT_READY` |
| `config` 不是 JSON object 或不满足 `accountConfigSchema` | 422 | `INTEGRATION_ACCOUNT_CONFIG_INVALID` |
| `accountConfigSchema` 自身在 registry 阶段非法 | 服务启动失败 | `MANIFEST_SCHEMA_INVALID` |

## 4. Channel Profile 目标定位

`channel_account` 直接重命名为 `channel_profile`，不再承载 credential 主身份，语义收敛为 channel runtime profile。

它负责：

1. channel 侧运行配置档案
2. assistant / scenario binding 配置
3. conversation binding 运行结果的 profile 归属
4. inbound 开关
5. provider job 配置关联
6. 对应 `integration_account.id` 的引用

它不负责：

1. 明文 credential
2. credential status 权威状态

当前目标架构直接使用 `channel_profile` 命名，不保留 `channel_account` 作为目标模型。

## 5. Credential Reference 暂不独立建表

当前不引入独立 `credential_reference` 表。

先把以下字段放在 `integration_account`：

```text
external_secret_ref
credential_status
```

如出现多凭证、多版本、轮换历史、审计快照需求，再拆：

```text
integration_account_credential
```

## 6. Credential Protocol

本节只适用于 descriptor 声明 credential endpoints 的 remote credential lifecycle。enterprise remote
descriptor 未声明 credential endpoints 时，不支持通过 Core API / Web 管理 credential；对应 Integration
Account 的 `credential_status` 可以保持 `NOT_CONFIGURED`，`external_secret_ref` 可以为空。core reference
descriptor 使用 Core-owned encrypted reference secret 时，credential 管理规则见 §2.2，不走本节的
remote endpoint protocol。

1. credential create
2. credential rotate
3. credential revoke
4. credential validate

Credential create / rotate / revoke / validate 都围绕 `integration_account.id` 执行。
这些协议动作只面向固定常量密钥的创建、人工替换、撤销和校验，不包含自动 refresh 或后台 status sync。

Credential create 的 Web-facing 目标流程是一步创建 Integration Account 并提交 credential：

```text
POST /api/integration/accounts
  subjectType + subjectId + name + config + optional credential
    -> API validates descriptor and accountConfigSchema
    -> API generates integration_account.id
    -> if credential is present, API validates credentialSchema
      -> remote credential lifecycle: calls extension createCredential
      -> Core-owned encrypted reference secret: encrypts credential locally
    -> API writes external_secret_ref or credential_ciphertext / credential_status
```

`accountId` 始终由 Core API 生成，只用于 Core 内部持久化、snapshot、校验和 internal credential resolver。remote extension 不接收也不依赖 `accountId`；它只通过自己返回的 `externalSecretRef` 定位 credential。即使 Web 在创建 account 时同步提交 credential，Core 也必须先生成 `integration_account.id`，再执行 remote `createCredential` 或本地加密写入。

enterprise remote descriptor 未声明 credential endpoints 时，Web-facing create Integration Account 只能提交非敏感 `config`，不得提交 credential。core reference descriptor 使用 Core-owned encrypted reference secret 时可以提交 credential，但由 API 本地加密保存。descriptor 声明 credential endpoints 但用户选择暂不配置 credential 时，也允许创建 `external_secret_ref = null`、`credential_status = NOT_CONFIGURED` 的 account。

`integration_account.name` 规则：

1. 创建 Integration Account 时 `name` 必填
2. `name` 是用户在 Web 上维护的本地账号展示名，可编辑
3. credential create / rotate / validate response 不回填、不覆盖 `integration_account.name`
4. 第一版不建模 credential metadata；账号展示统一使用 `integration_account.name`，需要展示的非敏感账号识别信息应放在 `integration_account.config` 或由 extension 私有管理面呈现

credential create 的适用边界：

1. 同一个 `integration_account.id` 只有在尚未成功获得 `external_secret_ref` 时才允许调用 `createCredential`
2. `NOT_CONFIGURED` 且 `external_secret_ref = null` 时可以首次 create
3. `VALIDATION_FAILED` 且 `external_secret_ref = null` 时可以重新提交 create
4. 一旦 `external_secret_ref` 有值，后续提交新的固定常量密钥必须使用 `rotateCredential`，不得再次调用 `createCredential`
5. `REVOKED` 是 terminal credential 状态；同一个 account 不允许 create again，用户需要新建 Integration Account
6. create 进行中不写入持久状态，只由同一个 `integration_account.id` 上的短时排他锁表达；重复提交返回 conflict
7. 一步创建 account + credential 时，若 account 已创建但 extension `createCredential` 失败，account 保留，`external_secret_ref = null`，`credential_status = VALIDATION_FAILED`，用户可在同一个 account 上重新提交 create credential

Credential lifecycle 不做 logical operation 幂等恢复，也不引入 credential operation 表。API 只需要对同一个
`integration_account.id` 做短时排他，防止前端重复点击或多个管理员同时触发 create / rotate / revoke /
validate。正在执行时，后续 credential lifecycle 请求返回 conflict；用户需要在当前操作返回后手动再次提交新的请求。

## 6.1 Account Status 与 Credential Status

`integration_account.status` 表达账号记录本身是否允许被业务配置引用。

```text
ENABLED
DISABLED
ARCHIVED
```

`integration_account.credential_status` 表达 credential material 的状态。

```text
NOT_CONFIGURED
ACTIVE
VALIDATION_FAILED
ROTATION_REQUIRED
REVOKE_FAILED
REVOKED
```

`NOT_CONFIGURED` 表示该 account 当前没有 Core-managed credential ref。典型场景：

1. descriptor 没有声明 credential endpoints
2. descriptor 声明了 credential endpoints，但管理员暂未通过 Core 配置 credential
3. extension 通过私有配置 / 私有管理面 / 私有存储维护 credential

credential lifecycle 调用是同步动作，不持久化 in-progress / pending 状态；执行中只由 account 级短时排他锁表示，锁冲突直接返回给 Web。

运行时可用性规则：

1. `status = ENABLED` 才允许新建绑定或生成新的 runtime snapshot
2. `DISABLED` / `ARCHIVED` account 不允许新建绑定，也不允许生成新的 runtime snapshot
3. `credential_status` 默认不作为生成 runtime snapshot 的硬门禁；`NOT_CONFIGURED`、`VALIDATION_FAILED`、`ROTATION_REQUIRED` 等状态都可以被管理员显式选择后进入配置
4. `REVOKE_FAILED` / `REVOKED` 的 account 不允许新建绑定，也不允许生成新的 runtime snapshot
5. runtime 不查询 `credential_status`，只透传 snapshot 中已有的 `externalSecretRef`

`credential_status` 初始值固定为 `NOT_CONFIGURED`。credential create 成功后转 `ACTIVE`；create 失败时转
`VALIDATION_FAILED`，由用户手动再次提交。

`credential_status` 允许迁移：

| From | To | 触发 |
| --- | --- | --- |
| `NOT_CONFIGURED` | `ACTIVE` | credential create 同步成功并返回可用 `externalSecretRef` |
| `NOT_CONFIGURED` | `VALIDATION_FAILED` | create 返回明确不可用或校验失败 |
| `ACTIVE` | `ACTIVE (ref unchanged)` | validate 成功或 rotate 成功后确认同一个 `external_secret_ref` 可用，状态保持 `ACTIVE` |
| `ACTIVE` | `VALIDATION_FAILED` | validate 明确失败 |
| `ACTIVE` | `ROTATION_REQUIRED` | validate 发现固定常量密钥不可继续使用，需要用户重新提交或管理员轮换 |
| `VALIDATION_FAILED` | `ACTIVE` | `external_secret_ref = null` 时 create 成功，或 `external_secret_ref` 有值时 validate / rotate 后恢复可用 |
| `VALIDATION_FAILED` | `ROTATION_REQUIRED` | 需要用户重新提交或轮换 |
| `ROTATION_REQUIRED` | `ACTIVE` | rotate 成功 |
| `ACTIVE` / `VALIDATION_FAILED` / `ROTATION_REQUIRED` | `REVOKED` | revoke 同步成功，`external_secret_ref` 清空或失效 |
| `ACTIVE` / `VALIDATION_FAILED` / `ROTATION_REQUIRED` | `REVOKE_FAILED` | revoke 同步失败 |
| `REVOKE_FAILED` | `REVOKED` | 管理员重试 revoke 并成功 |

非法迁移：

1. `REVOKED -> ACTIVE` 不允许；需要新建 Integration Account
2. `REVOKE_FAILED -> ACTIVE` 不允许；必须先完成 revoke 或人工确认后重新建 account
3. `REVOKED -> NOT_CONFIGURED` 不允许；同一个 account 不支持 create again
4. `ACTIVE` / `ROTATION_REQUIRED` / `REVOKE_FAILED` / `REVOKED` 且 `external_secret_ref` 有值或曾经有值时，不允许调用 `createCredential`
5. credential 状态机不负责决定 runtime snapshot 是否可生成；发布 / profile 更新必须同时校验 account status、subject 匹配和本节运行时可用性规则中的撤销态约束

## 7. Release Snapshot 规则

assistant release snapshot 可以保存 Tool Resource 对 `integration_account.id` 的引用，以及该 account 在发布时已有的 `externalSecretRef`。

Channel runtime profile 可以保存 `accountId`，并在启用 / 更新时固化当时已有的 `externalSecretRef` 供 outbound delivery 与 provider job 使用。未选择 account 或 account 没有 `externalSecretRef` 时，snapshot 中对应字段为空。

规则：

1. release snapshot / channel runtime profile 可以保存 `externalSecretRef`，不得保存明文 credential
2. runtime invocation 只使用 snapshot 内已有的 `externalSecretRef`，不回 API 查询最新 account / credential 状态
3. snapshot 缺少 `externalSecretRef` 不是 core runtime 协议错误；extension 可根据自身配置返回成功或失败
4. credential rotate 必须保持同一个 `externalSecretRef` 稳定，因此已有 ref 的 snapshot 通常不要求重发 assistant release
5. credential revoke 会使 extension 侧的 `externalSecretRef` 失效
6. `REVOKE_FAILED` / `REVOKED` 的 account 不允许生成新的 release snapshot 或 channel runtime profile；已存在 snapshot 不回收，后续运行时调用会在 extension 边界失败
7. `externalSecretRef` 不进入普通日志、metrics、runtime event、platform event、审计 diff 或导出文件

Tool Resource 到 assistant release snapshot 的字段物化规则以 `tool-connector.md §4.1` 为准。该规则固定为：
API publish 时物化，`agent-runtime` 只读 release snapshot，不回 API 查询 Integration Account、
credential status、`externalSecretRef` 或 connector definition。

## 8. No Credential Metadata

第一版不引入 `credential_metadata` 持久字段、response DTO 字段、展示白名单或 Web 展示区域。

规则：

1. credential lifecycle response 不返回 credential metadata；即使 extension 返回额外 metadata 字段，Core 也必须忽略，不保存、不展示、不写入审计 diff
2. Web 列表展示以 `integration_account.name` 作为主展示名，辅助状态只使用 `integration_account.status`、`credential_status` 和是否存在 `externalSecretRef` 这类 Core 权威字段
3. 非敏感账号识别信息如果需要由管理员维护，应建模在 `integration_account.config` 并受 `accountConfigSchema` 校验；不可由 credential endpoint 动态回填
4. 外部账号 ID、租户 ID、vault path、masked identifier 或可用于定位私有系统凭证的字段不进入 Core credential 状态模型；需要排障时使用 trace / audit 与 extension 私有日志关联

## 9. Credential Lifecycle Retry Policy

Credential create / rotate / revoke / validate 不引入后台重试任务，也不新增
credential lifecycle worker，也不持久化 logical operation idempotency state。

并发 / 重复提交控制：

1. API 对同一个 `integration_account.id` 的 credential lifecycle 操作加短时排他锁
2. 排他范围覆盖 create / rotate / revoke / validate，任一操作执行中，其他 credential lifecycle 操作返回 conflict
3. 排他锁只用于防止前端多次误操作或多管理员并发提交，不表达业务幂等
4. 锁可以用数据库行锁、状态条件更新或短 TTL lock 实现；进程异常后必须能释放或过期
5. API 不为 credential lifecycle 请求生成或持久化 logical operation `Idempotency-Key`
6. extension credential endpoint 可以接收普通 request id / trace id 用于排障，但 Core 不要求 extension 对 credential lifecycle 做跨请求幂等恢复

重试策略：

1. API 不做后台重试
2. API 调用内也不做幂等重试；transport timeout、connection reset、临时 5xx 等失败直接返回本次操作失败
3. 用户确认后可以在 Web 上手动再次提交新的 credential lifecycle 请求；这次提交是新的操作，不复用上次请求
4. 需要用户重新提交 credential material 的 create / rotate 永远由用户显式再次提交

失败后的恢复方式：

1. create 失败且未得到 `externalSecretRef`：account 进入 `VALIDATION_FAILED`，由用户手动再次提交；同一个 account 只允许在 `external_secret_ref = null` 时再次 create
2. rotate 失败：原 `externalSecretRef` 若仍有效则保持不变，并标记 `ROTATION_REQUIRED` / `VALIDATION_FAILED`
3. validate 失败：只更新校验状态，不创建 retry 队列
4. revoke 失败：进入 `REVOKE_FAILED`，由管理员重新触发 revoke 或人工处理

## 10. Credential Revoke Failure

credential revoke 失败时，`integration_account` 不得标记为已撤销。

`credential_status` 状态枚举见 §6.1，本节只描述 revoke 流程上的状态迁移。

revoke 流程：

1. revoke 开始时只获取同一个 `integration_account.id` 的短时排他锁，不写入 pending 状态
2. extension revoke 成功后 `credential_status` 置为 `REVOKED`，清空或失效 `external_secret_ref`
3. extension revoke 在本次调用内遇到 transient failure 时允许极少量瞬时重试
4. extension revoke 最终失败时置为 `REVOKE_FAILED`，记录 `last_error`
5. `REVOKE_FAILED` / `REVOKED` 的 account 不允许新建 Channel profile 或 Tool Resource 绑定，也不允许生成新的 runtime snapshot
6. 已存在 snapshot 不回收；后续 runtime invocation 如果仍持有旧 `externalSecretRef`，会在 extension 边界失败
7. `REVOKED` 不允许在同一个 account 上 create again；恢复使用新 Integration Account

`REVOKE_FAILED` 是需要人工处理的状态，不等同于 credential 仍可安全使用。
