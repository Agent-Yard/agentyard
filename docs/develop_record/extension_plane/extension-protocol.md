# Extension Protocol 与 SDK

## 1. 交付边界

一期交付 `packages/extension-protocol`、Java SDK 和 Python SDK。

`packages/extension-protocol` 是协议源，保存 OpenAPI、JSON Schema、examples 和 contract tests。

`packages/extension-sdk-jvm` 面向 Java / JVM extension 实现者，提供稳定 DTO、协议常量、错误模型、manifest 校验入口、HTTP client / server 辅助契约和 contract test 支撑。Java SDK 的相关 DTO、校验和辅助逻辑必须从 `packages/extension-protocol` 的契约生成，或被 contract tests 约束为与契约一致。

`packages/extension-sdk-python` 面向 Python extension 实现者，同时供 core 内的 Python `agent-runtime` 复用，提供与 Java SDK 等价的 DTO / 类型、协议常量、错误模型、manifest 校验入口、canonical JSON、HTTP client / server 辅助契约和 pytest contract test 支撑。Python SDK 的相关 DTO、校验和辅助逻辑必须从 `packages/extension-protocol` 的契约生成，或被 contract tests 约束为与契约一致。

当前不交付 TypeScript SDK 或其他语言 SDK。

```text
packages/extension-protocol/
  openapi/
    channel-provider-extension.yaml
    tool-connector-extension.yaml
    common-extension.yaml
  json-schema/
    channel-provider-manifest.schema.json
    tool-connector-manifest.schema.json
    ui-field.schema.json
    error.schema.json
  examples/
    channel-provider.feishu.json
    tool-connector.simple-http.json
  contract-tests/

packages/extension-sdk-jvm/
  src/main/java/
    com/lynxus/extension/sdk/
      common/
      channel/
      tool/
      credential/
      validation/
  src/test/java/

packages/extension-sdk-python/
  src/lynxus_extension_sdk/
    common/
    channel/
    tool/
    credential/
    validation/
    testing/
  tests/
```

## 2. 协议内容

当前至少定义：

1. manifest schema
2. invocation request / response DTO
3. credential create / rotate / revoke / validate DTO
4. shared `ExtensionError` / `ExtensionErrorCategory`
5. auth header contract
6. trace context contract
7. examples
8. contract test fixtures

## 2.0 Shared Field Spec

跨多个 endpoint 复用的字段在此集中定义，其他文档只引用。

本文件是以下 cross-cutting protocol 的唯一权威：

1. `idempotencyKey` / `Idempotency-Key` 字段规则
2. `traceContext` 字段规则
3. `externalSecretRef` 的协议传输规则
4. credential create / rotate / revoke / validate HTTP endpoint 契约
5. `ExtensionError` schema 与 `ExtensionErrorCategory`
6. user-editable config secret 边界

`integration_account` 持久化字段、账号状态和 credential 状态机由
`credentials-and-persistence.md` 维护；本文件只定义跨服务 HTTP 协议。

### `idempotencyKey` / `Idempotency-Key`

1. ASCII 字符集：`[A-Za-z0-9._:-]`
2. 长度 1-128
3. 大小写敏感
4. 同一 logical operation 的 retry 必须复用同一 key
5. 出现在 HTTP header (`Idempotency-Key`) 和 invocation envelope (`idempotencyKey`)，两处必须一致

该字段只适用于需要 remote side 幂等识别的 runtime invocation：

1. tool connector invoke
2. channel provider `sendOutbound`
3. channel provider `runJob`
4. remote provider 推送 `/internal/channel-events/normalized`

Credential lifecycle create / rotate / revoke / validate 不使用 `Idempotency-Key`，也不在 request envelope
中携带 `idempotencyKey`。Core API 只对同一个 `integration_account.id` 做短时排他，失败后由用户手动再次提交新的 credential lifecycle 请求。credential lifecycle 的排障关联使用 `traceContext`、`X-Lynxus-Trace-Id` 和 `X-Lynxus-Request-Id`。

### `traceContext`

1. JSON object，字段规则与 W3C Trace Context 兼容
2. 至少包含 `traceparent` string；可选 `tracestate` string、`baggage` object
3. core / extension 必须透传，不允许覆盖 upstream `traceparent`
4. 所有 descriptor-level request envelope 必须携带 `traceContext.traceparent`
5. service-level endpoint（`/extension/manifest`、remote extension 的 `/extension/health`、`/health/live`、`/health/ready`）没有 request envelope，不要求 `traceContext`，也不要求 `X-Lynxus-Trace-Id` / `X-Lynxus-Request-Id`

### `externalSecretRef`

1. opaque string，由 extension credential create / rotate 返回
2. 允许保存在 `integration_account`、assistant release snapshot、channel runtime profile 和 invocation envelope
3. core 不解析其内部结构
4. 长度上限 ≤ 512 字符；持久化列与协议 DTO 必须按此上限约束
5. 不出现在普通日志、metrics、runtime event、platform event、审计 diff 或导出文件

### User-editable Config Secret Boundary

普通 user-editable config 只能保存可审计、可展示、可复制的业务配置，不允许承载 secret。

禁止包含 secret 的配置范围：

1. channel provider `configSchema`
2. `jobConfigSchema`
3. tool connector `configSchema`
4. `operationMappingSchema`
5. `accountConfigSchema`
6. channel provider `defaultConfig`
7. schedule / job config defaults
8. platform assistant binding config
9. external template binding `variableSchema` / `displayName` / `externalEditUrl`

规则：

1. `secret=true` 只允许出现在 credential 表单相关 schema / UI schema 中
2. manifest validation 和 contract tests 必须拒绝普通配置 schema / default 中的 `secret=true`
3. 普通配置 schema / default 不得包含 credential 明文、API key、access token、refresh token、
   webhook signing secret、private key、password 或 `externalSecretRef`
4. 如果 provider job、channel profile、assistant binding、external template binding、tool connector config 或
   operation mapping 需要 secret，必须通过 Integration Account / credential endpoint /
   Core-owned encrypted reference secret / extension 私有配置或私有存储获取；普通 user-editable config 不得承载 secret
5. 普通配置可以保存非敏感引用，例如 `integration_account.id`、`credentialPurpose`、`externalTemplateId`、
   provider-native non-secret identity 或 extension 私有 alias；这些引用不得可逆还原 secret
6. Web 可以对 credential form 使用 password / secret 控件，但不得在普通配置表单中使用 secret 控件

## 2.1 Service Manifest

每个 extension service 必须暴露统一 manifest endpoint：

```text
GET /extension/manifest
```

“extension service” 包括企业 remote extension service 和 core runtime service（`channel-gateway` / `agent-runtime`）。core runtime service 同时承担 reference descriptor 的 host 角色，对外协议形态与企业 extension 完全一致。`worker` / `knowledge-service` 不属于本期 core runtime service，不暴露 `/extension/manifest`，也不参与 extension registry ownership。

响应是 service-level manifest envelope，而不是单个 descriptor manifest：

```json
{
  "extensionApiVersion": 1,
  "coreMinVersion": "0.8.0",
  "coreMaxVersion": "0.9.x",
  "descriptors": {
    "channelProviders": [],
    "toolConnectors": []
  }
}
```

规则：

1. 一个 service 可以同时返回多个 channel provider / tool connector descriptor
2. `extensionApiVersion` / `coreMinVersion` / `coreMaxVersion` 是 service envelope 级字段，所有 descriptors 共用
3. descriptor 内的 `providerType` / `connectorType` 是业务权威身份
4. 当前阶段 service envelope 不定义 `capabilities` 字段；diagnostic resolve / raw payload 反查不进入 manifest，也不在 descriptor 层声明，错误排障规则以 §2.3.1 为准
5. core 启动时按 actor 消费范围拉取 `GET /extension/manifest`，再按静态注册项 `exposes` 白名单校验 descriptor 集合：API 拉取全部 registration，`agent-runtime` 只拉取 `exposes.toolConnectorTypes` 非空的 registration，`channel-gateway` 只拉取 `exposes.channelProviderTypes` 非空的 registration
6. core runtime service（`channel-gateway` / `agent-runtime`）加载本服务对应且属于自身消费范围的 core preset registration 时，由内部 `DescriptorProvider` 直接提供同一份 envelope，不发起自身 HTTP 调用；其他属于自身消费范围的 service 通过 HTTP 拉取
7. `descriptors.channelProviders` 与 `descriptors.toolConnectors` 同时为空数组的 manifest 视为非法，manifest validation failed；没有 descriptor 的 service 不应进入 registration 集合
8. 当前阶段 core runtime service 必须暴露 core build 内置的 reference descriptor，不能用空 envelope 表达 core preset 不暴露能力

Endpoint path 规则：

1. `GET /extension/manifest` 是唯一固定的 core -> extension discovery path
2. descriptor `endpoints` 中的值是 extension-owned declared path
3. endpoint key 固定，path value 不固定；Core 不按 key 推导默认 path，也不要求 path 等于文档示例
4. declared path 必须以 `/` 开头
5. declared path 不允许包含 scheme、host、userinfo、fragment 或 query string
6. 静态注册项 `baseUrl` 允许 path prefix；Core 调用 descriptor endpoint 时使用 normalized `baseUrl` 的 path prefix 与 declared path 拼接
7. 拼接规则是 `normalizedBaseUrlWithoutTrailingSlash + declaredPath`；例如 `https://ext.example.com/lynxus` + `/tools/invoke` 得到 `https://ext.example.com/lynxus/tools/invoke`
8. Core 调用 endpoint 时只允许使用静态注册项 normalized `baseUrl` + declared path
9. extension manifest 不能覆盖或追加 invocation host

Manifest endpoint auth：

1. `/extension/manifest` HTTP 调用必须携带 `deployment-and-governance.md §5` 定义的 service-level internal auth header（`Authorization`）
2. 调用方应该携带 `X-Lynxus-Extension-Registration-Id` 作为部署观测上下文，便于 extension service 日志和排障定位；extension service 不得把该 header 当作授权事实源
3. `/extension/manifest` 是 service-level endpoint，调用前没有唯一 descriptor；调用方不得要求或发送 `X-Lynxus-Extension-Descriptor-Type` / `X-Lynxus-Extension-Descriptor-Id`
4. descriptor 白名单、允许暴露哪些 `providerType` / `connectorType`、以及 manifest 返回内容是否匹配 registration，始终由 Core 侧静态 registration config 校验
5. core runtime service 自加载本服务 preset 时走内部 `DescriptorProvider`，不发起 HTTP，也不需要 header
6. manifest fetch 不属于 mutating invocation，不强制 `Idempotency-Key`，也不要求 `X-Lynxus-Trace-Id` / `X-Lynxus-Request-Id`

Descriptor source 规则：

1. 所有 descriptor 都来自某个 registration 的 `/extension/manifest` envelope；runtime 自加载本服务 preset 只是用同一个 `DescriptorProvider` 跳过自身 HTTP，不建立第二套 descriptor 事实源
2. core runtime service 通过内部 `DescriptorProvider` 同时驱动 HTTP `/extension/manifest` 和本服务 in-process registry 加载，两条出口共用同一份 descriptor 序列化结果
3. 是否发起 HTTP 是物理优化（自身加载本服务 preset 时直接读 provider）；协议事实始终是 manifest envelope
4. API definition registry 对 Web 暴露统一 definition，所有 descriptor 走同一套 schema 和 contract tests 约束

## 2.2 Credential Endpoints

credential create / rotate / revoke / validate 是 Tool Connector 和 Channel Provider 共用的可选 control-plane 协议。

Credential endpoints 只覆盖固定常量密钥类型的创建、人工替换、撤销和校验。协议不提供 token refresh endpoint，也不允许 runtime owner 通过 provider job 发起 credential refresh。若 extension 需要短期 access token、refresh token 或 OAuth token refresh，必须由 extension 在自身私有配置 / 私有存储内维护；Core 不参与这类 refresh。

descriptor 可以声明 credential endpoints。声明后表示该 descriptor 支持由 Core API / Web 发起 credential lifecycle 操作；不声明则表示 credential 完全由 extension 私有配置、私有管理面或私有存储维护，Core 不创建、不轮换、不校验该 credential：

```json
{
  "credentialSchema": {},
  "endpoints": {
    "createCredential": "/credentials",
    "rotateCredential": "/credentials/rotate",
    "revokeCredential": "/credentials/revoke",
    "validateCredential": "/credentials/validate"
  }
}
```

HTTP 语义：

1. `createCredential` 对应 `POST {endpoints.createCredential}`
2. `rotateCredential` 对应 `POST {endpoints.rotateCredential}`
3. `revokeCredential` 对应 `POST {endpoints.revokeCredential}`
4. `validateCredential` 对应 `POST {endpoints.validateCredential}`
5. 四个 endpoint 全部使用 `POST`，不接受 `GET` / `PUT` / `DELETE`
6. credential path 由 descriptor manifest 声明；`/credentials`、`/credentials/rotate`、`/credentials/revoke`、`/credentials/validate` 只是推荐示例，不是协议强制值
7. Core 不对未声明的 credential endpoint 做默认 path fallback

Credential lifecycle request 共享字段：

1. 四个 request body 都必须携带 `descriptor`、`account` 和 `traceContext`
2. `descriptor.type` 固定为 `TOOL_CONNECTOR` / `CHANNEL_PROVIDER`，`descriptor.id` 固定为对应 `connectorType` / `providerType`
3. `descriptor` 是 credential lifecycle request body 内唯一 descriptor 身份事实源；HTTP header 不携带 `X-Lynxus-Extension-*`
4. `account.config` 必填但可为空 object；它是 Core 已按 descriptor `accountConfigSchema` 校验过的非敏感 account config
5. request 不携带 Core 内部 `accountId`；remote extension 只使用 `externalSecretRef` 作为 credential handle
6. `traceContext.traceparent` 必填；`X-Lynxus-Trace-Id` 必须与该 trace 同源
7. request 不携带 `idempotencyKey`，HTTP header 也不携带 `Idempotency-Key`

Credential lifecycle success response 共享字段：

1. 2xx response 不回显 `descriptor`、`account`、`traceContext` 或 request credential
2. 第一版 response 不包含 `credentialMetadata`；Core 不保存、不展示 extension 返回的 credential metadata，若 response 出现未定义 metadata 字段必须忽略
3. 操作级失败使用 `ExtensionError`；除 validate 明确返回 credential 校验结果外，不在 2xx response 中表达失败状态

### `createCredential`

request：

```json
{
  "descriptor": {
    "type": "TOOL_CONNECTOR",
    "id": "enterprise.acme.crm"
  },
  "account": {
    "config": {}
  },
  "credential": {
    "apiKey": "secret-value"
  },
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  }
}
```

字段规则：

1. `credential` 必填，必须是 JSON object；它是 Web credential 表单提交并由 Core 按 descriptor `credentialSchema` 校验后的固定常量密钥材料
2. create request 不携带 `externalSecretRef`；已有 `externalSecretRef` 的 account 不得再次调用 `createCredential`

success response：

```json
{
  "externalSecretRef": "vault://opaque-ref",
  "credentialStatus": "ACTIVE"
}
```

成功规则：

1. `externalSecretRef` 必填，长度和存储规则引用 §2.0
2. create 成功只允许返回 `credentialStatus = ACTIVE`

### `rotateCredential`

request：

```json
{
  "descriptor": {
    "type": "TOOL_CONNECTOR",
    "id": "enterprise.acme.crm"
  },
  "account": {
    "config": {},
    "externalSecretRef": "vault://opaque-ref"
  },
  "credential": {
    "apiKey": "new-secret-value"
  },
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  }
}
```

字段规则：

1. `account.externalSecretRef` 必填，必须来自当前 `integration_account.external_secret_ref`
2. `credential` 必填，必须是 JSON object；它是用户 / 管理员新提交的固定常量密钥材料
3. Core 在 `externalSecretRef` 缺失时不得调用 `rotateCredential`
4. extension 必须在同一个 `externalSecretRef` 背后替换 credential material，不得为 rotate 分配新的 ref

success response：

```json
{
  "externalSecretRef": "vault://opaque-ref",
  "credentialStatus": "ACTIVE"
}
```

成功规则：

1. `externalSecretRef` 必填，且必须等于 request `account.externalSecretRef`
2. rotate 成功只允许返回 `credentialStatus = ACTIVE`
3. rotate 失败使用 `ExtensionError`；Core 保留原 `externalSecretRef`，并按 `credentials-and-persistence.md §9` 更新 `credential_status`

### `validateCredential`

request：

```json
{
  "descriptor": {
    "type": "TOOL_CONNECTOR",
    "id": "enterprise.acme.crm"
  },
  "account": {
    "config": {},
    "externalSecretRef": "vault://opaque-ref"
  },
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  }
}
```

字段规则：

1. `account.externalSecretRef` 必填，必须来自当前 `integration_account.external_secret_ref`
2. validate request 不携带 `credential`
3. Core 在 `externalSecretRef` 缺失时不得调用 `validateCredential`

success response：

```json
{
  "credentialStatus": "ACTIVE"
}
```

成功规则：

1. `credentialStatus` 必填，允许值为 `ACTIVE` / `VALIDATION_FAILED` / `ROTATION_REQUIRED`
2. validate 返回 `VALIDATION_FAILED` / `ROTATION_REQUIRED` 是明确的 credential 校验结果，不是 protocol error
3. validate 遇到 transport、鉴权、远端系统不可用或 extension 内部错误时使用 `ExtensionError`；Core 不把这类错误伪装成明确 credential 校验结果
4. validate response 不返回 `externalSecretRef`

### `revokeCredential`

request：

```json
{
  "descriptor": {
    "type": "TOOL_CONNECTOR",
    "id": "enterprise.acme.crm"
  },
  "account": {
    "config": {},
    "externalSecretRef": "vault://opaque-ref"
  },
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  }
}
```

字段规则：

1. `account.externalSecretRef` 必填，必须来自当前 `integration_account.external_secret_ref`
2. revoke request 不携带 `credential`
3. Core 在 `externalSecretRef` 缺失时不得调用 `revokeCredential`

success response：

```json
{
  "credentialStatus": "REVOKED"
}
```

成功规则：

1. revoke 成功只允许返回 `credentialStatus = REVOKED`
2. revoke success 表示 extension 已使 request `account.externalSecretRef` 不再可解析
3. revoke response 不返回 `externalSecretRef`
4. revoke 失败使用 `ExtensionError`；Core 不得标记为已撤销，并按 `credentials-and-persistence.md §10` 进入 `REVOKE_FAILED`

规则：

1. credential lifecycle 是 descriptor 的可选能力，不是 registry 必备能力
2. descriptor 不声明 credential endpoints 时，manifest validation 不得因为 `credentialSchema` 缺失或 endpoints 缺失而失败
3. descriptor 一旦声明任一 credential endpoint，就必须同时声明 `credentialSchema` 和四个 credential endpoint；缺任一项属于 manifest schema invalid
4. `credentialSchema` 只服务 Core/Web 的 credential 表单和 API 校验；enterprise remote descriptor 没有 credential endpoint 时不得用 `credentialSchema` 暗示 Core 仍需管理 credential
5. core runtime service 暴露的 built-in reference descriptor 可以声明 `credentialSchema` 但不声明 credential endpoints；这表示 credential material 由 Core-owned encrypted reference secret 存储，规则见 `credentials-and-persistence.md §2.2`
6. manifest validation 只做 shape 校验：字段存在性、endpoint path 形态、JSON Schema 自身合法性；credential 是否已经在 extension 私有侧配置、是否能成功调用外部系统，不在 registry 阶段校验
7. descriptor 声明 credential endpoints 时，Core API 是 credential invocation owner，remote extension service 是 credential material 的处理方；没有 credential endpoint 时 Core API 不暴露该 descriptor 的 remote credential create / rotate / revoke / validate action
8. 即使 descriptor 声明了 credential endpoints，管理员也可以不通过 Core 配置 credential；创建绑定、发布 release 或更新 channel profile 时不因 `externalSecretRef` 缺失而阻塞，运行时由 extension 按自身规则返回成功或失败
9. credential endpoints 必须使用 shared auth header、trace context 和 `ExtensionError`；不使用 HTTP `Idempotency-Key`，request / response DTO 也不包含 `idempotencyKey`
10. credential endpoints 不返回明文 credential，也不返回 credential metadata；create 返回 `externalSecretRef` 和状态，rotate 返回同一个 `externalSecretRef` 和状态，validate / revoke 返回状态
11. `agent-runtime` / `channel-gateway` 不发起 remote credential lifecycle invocation，也不在 remote runtime invocation 前回 API 读取 credential 数据或账号状态；core-owned encrypted reference secret 的 in-process adapter 可以通过 internal resolver 按 `accountId` 取明文 credential
12. `rotateCredential` 表达用户 / 管理员提交新的固定常量密钥并更新同一 `externalSecretRef` 背后的 credential material，不表达 access token refresh
13. Core 只在 account 尚未持有 `externalSecretRef` 时调用 `createCredential`；同一个 account 一旦已有 ref，后续密钥替换必须调用 `rotateCredential`
14. `REVOKED` account 不允许通过 `createCredential` 复活；需要新建 Integration Account

## 2.3 Common Protocol

Tool Connector 和 Channel Provider 共用基础错误模型：

```json
{
  "errorCode": "REMOTE_AUTH_FAILED",
  "message": "credential expired",
  "category": "AUTH",
  "retryable": false,
  "details": {}
}
```

基础 `category`：

```text
AUTH
BAD_REQUEST
REMOTE_TIMEOUT
REMOTE_UNAVAILABLE
REMOTE_RATE_LIMITED
REMOTE_BUSINESS_REJECTED
PROTOCOL_ERROR
CIRCUIT_OPEN
UNKNOWN
```

`CIRCUIT_OPEN` 只由 core circuit breaker 在 open 状态下产生，extension 自身不应返回。它在 retry 合并规则中视为 `retryable = true`。如果 extension 误返回 `CIRCUIT_OPEN`，core runtime 必须将其重映射为 `PROTOCOL_ERROR` 并写审计事件，不允许按 `CIRCUIT_OPEN` 语义透传。

分域映射：

```text
Tool Connector Extension
  -> ExtensionError
  -> ToolExecutionFailure

Channel Provider Extension
  -> ExtensionError
  -> ChannelProviderFailure
```

extension 只表达远端 / 协议错误，core 决定这些错误对 Agent、Playbook、Channel Delivery 或 Provider Job 的业务含义。

Tool Connector 和 Channel Provider 必须使用同一个完整 `ExtensionError` schema，不允许分域裁剪字段。

分域差异通过 `category` / `errorCode` 映射表达，不通过改变 schema shape 表达。不适用于某个领域的字段保持 optional 或 `null`。

字段约束：

1. `category` 是稳定枚举，用于 core 统一判断错误类别
2. `errorCode` 是 extension / domain-specific string，用于细粒度排障
3. `details` 必填；没有额外信息时使用空 object `{}`，且只允许非敏感结构化信息

### 2.3.1 原始错误 / 响应排障

第一版不引入 diagnostic resolve endpoint，也不在协议中保留 raw error / raw response /
raw payload 的 opaque reference 字段。原始错误 / 原始响应 / 私有 payload 由 extension 在自己日志、
审计或私有工单系统中保留；Core 不解析、不存 opaque ref，也不发起反查请求。

排障路径：

1. Core 端：从日志 / metrics / audit 中按 `traceContext.traceparent`、`registrationId`、
   `descriptorType` / `descriptorId`、`errorCode`、`category` 检索
2. Extension 端：用同一份 trace id 在 extension 私有日志 / 审计中检索原始 payload
3. 跨边界排障靠双方共享 trace id 拼接，不通过 HTTP 协议反查

如果未来确实需要协议级反查，再单独引入 capability 字段、resolve endpoint、
鉴权与审计规则；当前阶段不做。

服务发现规则：

1. API 使用同一份 Extension Definition Registry 反查 `descriptorType + descriptorId -> registration`
2. credential lifecycle invocation、manifest reload 和 account subject 校验都必须复用这张反查表
3. 反查表来源是启动时合并后的 extension registration config 加 manifest 白名单校验结果，不单独暴露 Web 业务查询 API
4. 找不到唯一 registration、descriptor 不在 registration 白名单内时，API 必须拒绝调用并写审计事件
5. API 调用目标只能是 `registration.baseUrl + 对应 declared endpoint path`，不能使用 account config 或用户输入覆盖 host

## 2.4 契约源关系

OpenAPI 和 JSON Schema 都保留，但职责边界不同。

`packages/extension-protocol` 只承载 extension 边界协议。这里的 OpenAPI 是 extension service
HTTP protocol operation 的唯一源，覆盖 core -> extension 和 extension -> core 两类跨 extension
边界契约，不承载 Web-facing control-plane API，也不承载 core -> core internal admin API。

1. 固定 endpoint 的 path / method
2. manifest-declared endpoint key 的 method
3. request / response DTO
4. headers
5. HTTP error response
6. 跨语言实现文档
7. mock / contract test client / server stub 的契约输入

OpenAPI 覆盖的 core -> extension 固定 endpoint：

```text
/extension/manifest
```

OpenAPI 覆盖的 core -> extension manifest-declared operation：

```text
toolConnector.endpoints.invoke -> POST {declared path}
channelProvider.endpoints.sendOutbound -> POST {declared path}
channelProvider.endpoints.runJob -> POST {declared path} when jobDefinitions is non-empty
descriptor.endpoints.createCredential -> POST {declared path}
descriptor.endpoints.rotateCredential -> POST {declared path}
descriptor.endpoints.revokeCredential -> POST {declared path}
descriptor.endpoints.validateCredential -> POST {declared path}
```

`packages/extension-protocol` OpenAPI 覆盖的 extension -> core endpoint：

```text
/internal/channel-events/normalized
```

以下接口不属于 `packages/extension-protocol` 的协议事实源。它们是 core 内部或 Web-facing
control-plane 契约，仍由 `packages/contracts/openapi/*` 维护：

Core registry / runtime internal endpoint：

```text
/internal/extension-registry/tool-connectors/validation
/internal/extension-registry/channel-providers/validation
/internal/extension-registry/validation
```

Channel admin internal endpoint，事实源是
`packages/contracts/openapi/channel-gateway-internal.yaml`：

```text
/internal/channel-admin/profiles
/internal/channel-admin/profiles/{channelProfileId}
/internal/channel-admin/profiles/{channelProfileId}/template-bindings
/internal/channel-admin/profiles/{channelProfileId}/template-bindings/{assistantId}/{messageType}/{messageSubtype}/{messageVersion}
/internal/channel-admin/profiles/{channelProfileId}/jobs
/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}
/internal/channel-admin/profiles/{channelProfileId}/jobs/{jobType}/runs
```

Web-facing `/api/channel-admin/*` 由 `packages/contracts/openapi/control-plane.yaml` 覆盖；它与
channel-gateway internal DTO 由 API 层显式映射，不共享隐式 DTO。

Web-facing definition endpoint 属于 control-plane API，不属于 enterprise extension service 或 runtime internal invocation 协议。落地时应由 `packages/contracts/openapi/control-plane.yaml` 覆盖：

```text
GET /api/extensions/channel-providers -> ApiResponseChannelProviderDefinitionList
GET /api/extensions/tool-connectors -> ApiResponseToolConnectorDefinitionList
```

Extension service health endpoint 可以纳入 `packages/extension-protocol` OpenAPI，但它们不是
extension invocation 契约：

```text
/health/live
/health/ready
/extension/health
```

`/extension/health` 是 remote enterprise extension service 的诊断 endpoint。core preset
registration 对应的 `channel-gateway` / `agent-runtime` 不要求暴露 `/extension/health`；
部署 smoke check 对 core preset 只调用服务自身 `/health/ready` 和 registry validation endpoint。

Health endpoint 第一版只服务 smoke check、状态展示和运维诊断，不参与 Core registry startup validation，也不引入 registry validation error code。

extension 边界固定 endpoint 的 path / method / header / DTO / error response 以
`packages/extension-protocol` 的 OpenAPI bundle 为准。manifest-declared operation 的 method /
header / DTO / error response 以该 OpenAPI 为准，实际 path 以 service manifest 为准。
Web-facing API 和 core -> core internal API 以 `packages/contracts/openapi/*` 为准。文档可以解释语义，但不得另行定义不同字段。

JSON Schema 是 manifest / config / UI schema 的唯一源：

1. channel provider manifest
2. tool connector manifest
3. service manifest envelope
4. `accountConfigSchema`
5. `credentialSchema`
6. channel provider / tool connector `configSchema`
7. `operationMappingSchema`
8. `job-definition.schema.json`
9. `schedule-config.schema.json`
10. `assistant-binding.schema.json`
11. external template binding `variableSchema`
12. UI field schema
13. registry / SDK manifest validator 的契约输入

语言 SDK 依赖这两类契约源：

1. extension 边界 HTTP DTO、client / server 辅助契约从 `packages/extension-protocol` OpenAPI 生成，或被 OpenAPI contract tests 约束
2. manifest / config validator 从 JSON Schema 生成或直接内置 JSON Schema validator
3. SDK 手写逻辑不得成为协议事实源；协议事实只来自 `packages/extension-protocol`

代码生成工具固定如下：

1. JVM SDK 使用 OpenAPI Generator（Gradle plugin / CLI）从 `packages/extension-protocol` OpenAPI 生成 Java DTO、enum、client / server helper 所需的协议模型；生成目录固定在 Gradle build 目录，例如 `packages/extension-sdk-jvm/build/generated/*`
2. Python SDK 使用 `datamodel-code-generator` 从 `packages/extension-protocol` OpenAPI / JSON Schema 生成 Pydantic v2 model；生成目录固定在 Python build 目录，例如 `packages/extension-sdk-python/build/generated/*`
3. Python SDK 当前不采用 `openapi-python-client` 生成完整 client package；HTTP client / server helper 由 SDK 手写薄封装，内部使用生成模型或被 contract tests 约束的手写 facade
4. JSON Schema validation 不通过生成代码实现；Java / Python SDK 直接使用运行时 JSON Schema validator 校验 manifest、config、UI schema 和 examples
5. canonical JSON、duplicate key 检测、Lynxus canonical JSON profile 输入约束和 digest 计算是 SDK 手写 shared helper，通过跨语言 fixtures 约束，不由 OpenAPI / JSON Schema generator 生成；当前阶段不引入第三方 JCS 库作为信任根

生成代码规则：

1. 对 extension 边界协议而言，`packages/extension-protocol` 内的 OpenAPI / JSON Schema 是唯一提交的协议事实源
2. 由 `packages/extension-protocol` OpenAPI / JSON Schema 生成的 DTO、client、server stub、validator glue 或 SDK 辅助代码不提交到仓库
3. 生成产物只写入对应构建目录，例如 Gradle `build/generated/*` 或 Python package build cache，不进入 `src/main` / `src` 源目录
4. SDK 源码可以包含少量手写 facade、runtime helper、错误映射和测试 helper，但这些手写代码必须被 contract tests 约束，不得重新定义字段、enum、endpoint、header 或 schema
5. CI 通过 protocol self-check、Java SDK contract tests、Python SDK contract tests 和 `agent-runtime` SDK 复用测试防止实现漂移；不通过提交 generated source 再做 diff 的方式建立第二事实源
6. 如果某个生成器要求产物参与编译，生成任务必须作为 build / test 的前置步骤自动执行，不要求开发者手动生成或提交产物

OpenAPI 不承载动态 config schema。JSON Schema 不重复描述 HTTP DTO。

UI schema 文件级约束：

1. UI schema 是 descriptor 内对应数据 schema 的 sibling 字段，不嵌入 JSON Schema 内部
2. `accountConfigSchema` 对应 `accountConfigUiSchema`
3. `credentialSchema` 对应 `credentialUiSchema`
4. tool connector `configSchema` 对应 `configUiSchema`
5. tool connector `operationMappingSchema` 对应 `operationMappingUiSchema`
6. channel provider `configSchema` 对应 `configUiSchema`
7. `jobDefinitions[*].jobConfigSchema` 对应 `jobDefinitions[*].jobConfigUiSchema`
8. UI schema 的值是 UI field array；空数组表示没有额外展示 hint
9. UI field schema 文件固定为 `packages/extension-protocol/json-schema/ui-field.schema.json`
10. UI field `key` 必须是 RFC 6901 JSON Pointer，例如 `/tenantId`、`/auth/mode`
11. `key` 只能指向对应 JSON Schema 中的 object property；第一版不支持指向 array item、`additionalProperties`
    或运行时动态字段
12. 如果属性只出现在 `oneOf` / `anyOf` 分支中，顶层或当前 object schema 也必须在 `properties`
    中声明同名 property，供 UI schema 建立稳定引用
13. 同一个 UI schema 内 `key` 必须唯一；`order` 只影响展示顺序，不影响数据契约
14. UI schema 不允许定义数据字段；manifest validator 必须拒绝找不到对应 JSON Schema property 的
    UI field

`visibilityCondition` 固定为可静态校验的结构化表达式，不允许脚本、表达式字符串或远端 URL：

```json
{
  "field": "/auth/mode",
  "operator": "equals",
  "value": "apiKey"
}
```

支持的 operator：

```text
equals
notEquals
in
notIn
exists
notExists
```

`visibilityCondition.field` 与 UI field `key` 一样使用 RFC 6901 JSON Pointer，并且必须引用同一个
JSON Schema 中存在的 property。`in` / `notIn` 的 `value` 必须是非空数组。

manifest validator / protocol self-check 必须做以下 cross-schema consistency 检查：

1. 每个 `*UiSchema` 都必须通过 `ui-field.schema.json`
2. 每个 UI field `key` 和 `visibilityCondition.field` 都必须能解析到对应 JSON Schema property
3. `select` / `radio` / `checkboxGroup` 的 options 必须来自对应 JSON Schema 的静态枚举：
   `enum`、`oneOf[*].const` / `oneOf[*].title`，或与 JSON Schema enum 完全一致的 label hint
4. 普通 user-editable config 的 UI schema 不允许 `secret=true` 或 `component=password`；
   只有 `credentialUiSchema` 可以使用 secret 输入控件
5. UI field `required=true` 只能重复 JSON Schema 已表达的 required；如果 JSON Schema 没有表达对应
   required，manifest validation 必须失败，validator 不得把 UI hint 提升为数据契约
6. 如果 UI field 有 `visibilityCondition`，但对应 property 在同一 object schema 的无条件
   `required` 中，manifest validation 必须失败
7. 条件 required 只能通过 JSON Schema `if` / `then` / `else`、`oneOf` 或 `dependentRequired`
   表达；只写在 UI field 上必须失败对应 contract fixture
8. 对第一版 validator 无法静态判定的复杂 UI / JSON Schema 关系，manifest 作者必须把条件拆成
   可识别的 `const` / `enum` 分支；否则 protocol self-check 按冲突处理

`packages/extension-protocol` 必须提供下列最小 fixtures，供 protocol self-check、Java SDK、
Python SDK、reference extensions 和 enterprise extension contract tests 复用：

```text
contract-tests/fixtures/manifest-valid/config-ui-schema.json
contract-tests/fixtures/manifest-valid/credential-secret-ui-schema.json
contract-tests/fixtures/manifest-valid/schedule-job-config-ui-schema.json
contract-tests/fixtures/manifest-valid/operation-mapping-ui-schema.json
contract-tests/fixtures/manifest-valid/conditional-required-json-schema.json
contract-tests/fixtures/manifest-invalid/ui-key-missing-property.json
contract-tests/fixtures/manifest-invalid/ui-secret-in-normal-config.json
contract-tests/fixtures/manifest-invalid/ui-required-without-json-schema-required.json
contract-tests/fixtures/manifest-invalid/ui-hidden-but-unconditional-required.json
contract-tests/fixtures/manifest-invalid/ui-options-drift-from-json-schema-enum.json
```

每个 invalid fixture 必须声明预期错误 code，至少覆盖：
`UI_SCHEMA_PROPERTY_NOT_FOUND`、`UI_SCHEMA_SECRET_NOT_ALLOWED`、
`UI_SCHEMA_REQUIRED_NOT_AUTHORITATIVE`、`UI_SCHEMA_VISIBILITY_REQUIRED_CONFLICT`、
`UI_SCHEMA_OPTIONS_DRIFT`。

Canonical JSON fixtures 使用 `packages/extension-protocol/contract-tests/fixtures/canonical-json/`
下的 JSON manifest 描述，不依赖某一种语言的 snapshot。每个 fixture 至少包含：

```json
{
  "name": "object-key-order",
  "input": "{\"b\":2,\"a\":1}",
  "expectedCanonicalUtf8Hex": "7b2261223a312c2262223a327d",
  "expectedDigest": "sha256:...",
  "expectedErrorCode": null
}
```

失败类 fixture 使用 `expectedErrorCode`，`expectedCanonicalUtf8Hex` 和 `expectedDigest` 为 `null`。
错误码至少覆盖：

```text
CANONICAL_JSON_DUPLICATE_KEY
CANONICAL_JSON_INVALID_UNICODE
CANONICAL_JSON_UNSAFE_INTEGER
CANONICAL_JSON_UNSUPPORTED_NUMBER
CANONICAL_JSON_UNSUPPORTED_VALUE
```

fixture 必须覆盖：

1. object key UTF-16 排序，包括非 ASCII key
2. string escape、control char、Unicode 和反斜杠 / quote
3. array 内 object、空 object、空 array、null、boolean
4. safe integer 上下界：`-9007199254740991` / `9007199254740991`
5. float、decimal、exponent、负零和超出 safe integer 的 number 拒绝
6. duplicate key 拒绝
7. descriptor definition digest 与 `registrationConfigDigest` 输入字段集合

Conditional required 规则：

1. `visibilityCondition` 属于 UI 展示 hint，不参与数据契约判定
2. 条件必填、条件类型、互斥字段和模式分支必须使用 JSON Schema 表达
3. 推荐使用 `if` / `then` / `else` 表达条件必填，使用 `oneOf` 表达互斥模式分支，
   使用 `dependentRequired` 表达“有 A 就必须有 B”
4. 后端校验、SDK validator 和 contract tests 只信 JSON Schema，不信 UI field schema
5. manifest validation / contract tests 必须识别 UI schema 与 JSON Schema 的明显冲突；
   例如字段被 `visibilityCondition` 隐藏，但在同一 JSON Schema 分支中仍 required
6. UI 不得通过提交前补默认值来掩盖 JSON Schema 分支表达缺失

## 2.5 Contract Test 运行方式

Contract tests 属于 `packages/extension-protocol`。Java SDK、Python SDK、reference extensions 和企业 extension implementation 必须跑同一套协议 fixtures。

Contract tests 分四类运行。

### 2.5.1 Protocol Self-check

在 core repo 内运行，验证协议资产本身合法，不依赖任何 extension service：

```text
packages/extension-protocol
  -> validate OpenAPI
  -> validate JSON Schema
  -> validate examples against schemas
  -> validate shared schema consistency
  -> validate UI schema references and UI / JSON Schema consistency fixtures
```

使用位置：

1. core repo CI 必跑
2. 协议文件变更时本地自检
3. 防止 OpenAPI、JSON Schema、examples 互相漂移

### 2.5.2 Java SDK Contract Tests

在 `packages/extension-sdk-jvm` 内运行，验证 SDK 与协议源一致：

```text
packages/extension-sdk-jvm
  -> generated / handwritten DTO matches OpenAPI
  -> manifest validator matches JSON Schema
  -> ExtensionError enum / fields matches protocol
  -> examples can be parsed / validated by SDK
```

使用位置：

1. Java SDK CI 必跑
2. core repo CI 必跑
3. 防止 SDK 成为第二套协议事实源

### 2.5.3 Python SDK Contract Tests

在 `packages/extension-sdk-python` 内运行，验证 SDK 与协议源一致：

```text
packages/extension-sdk-python
  -> generated / handwritten DTO matches OpenAPI
  -> manifest validator matches JSON Schema
  -> ExtensionError enum / fields matches protocol
  -> canonical JSON output matches shared fixtures
  -> examples can be parsed / validated by SDK
```

使用位置：

1. Python SDK CI 必跑
2. core repo CI 必跑
3. `apps/agent-runtime` CI 必须跑，确认 runtime 使用的协议实现来自 SDK 而不是本地分叉
4. 防止 SDK 成为第二套协议事实源

### 2.5.4 Extension Implementation Contract Tests

每个 provider / connector implementation 都要跑 contract tests。它适用于：

1. 企业 remote extension service
2. `channel-gateway`（作为 core preset registration 的 manifest host）
3. `agent-runtime`（作为 core preset registration 的 manifest host）

```text
extension service under test
  -> start local service
  -> load /extension/manifest
  -> validate service manifest envelope and descriptors against JSON Schema
  -> call extension-owned declared endpoints with protocol fixtures
  -> validate responses against OpenAPI
  -> validate error responses against ExtensionError
```

`channel-gateway` / `agent-runtime` 必须分别跑两次 contract tests：一次走 HTTP `/extension/manifest`（验证对外协议），一次走内部 `DescriptorProvider`（验证内部 API），两次结果的 envelope 必须 byte-for-byte 相同。

`/internal/channel-events/normalized` 是 Core internal endpoint，不属于 enterprise extension service 必须暴露的 endpoint。它由 core repo 的 channel-gateway contract / integration tests 覆盖；enterprise remote provider contract tests 只需要验证其 client 发送的 request DTO 符合 OpenAPI fixture。

`agent-runtime` 的 Python 实现必须通过 `packages/extension-sdk-python` 使用同一套 manifest envelope 序列化、JSON Schema 校验、`ExtensionError` 解析和 canonical JSON 实现，并在 `agent-runtime` CI 中阻断不一致实现。`agent-runtime` 不得把本地 DTO 或手写解析行为变成第二套协议事实源。

运行形态：

```text
packages/extension-protocol/contract-tests
  shared fixtures + test runner spec

packages/extension-sdk-jvm
  JUnit contract test helpers

packages/extension-sdk-python
  pytest contract test helpers

enterprise extension repo
  imports language SDK test fixtures / helpers
  runs contract tests against local service
```

原则：

1. `packages/extension-protocol` 提供标准 fixtures，不绑定实现语言
2. `packages/extension-sdk-jvm` 提供 Java / JUnit helper，方便 JVM extension 跑同一套 fixtures
3. `packages/extension-sdk-python` 提供 Python / pytest helper，方便 Python extension 和 `agent-runtime` 跑同一套 fixtures
4. 企业 extension CI 必须跑 contract tests 后才能发布 image
5. core reference extensions 必须跑同样 contract tests，作为样板实现
6. contract tests 只验证协议符合性，不验证业务正确性
7. canonical JSON 固定采用 `LynxusCanonicalJson` helper：基于 RFC 8785/JCS 的确定性 object key 排序、字符串转义和 UTF-8 bytes 输出思路，但输入收窄为 descriptor / registration digest 场景的安全 profile；digest 为 `sha256` over UTF-8 canonical bytes，输出形态 `sha256:<lowercase-hex>`
8. Java SDK 与 Python SDK 各自实现 helper，不引入第三方 JCS 库作为信任根；允许复用 Jackson 3 / Python 标准库 `json` 做 tokenization，但 canonical bytes emitter、duplicate key rejection、number/profile validation 必须由 SDK helper 统一负责
9. canonical JSON fixtures 是 descriptor digest 和 registry startup validation 的硬门禁；fixtures 必须覆盖 JSON Schema 内嵌对象、默认值、Unicode / escape、非 ASCII key 排序、数组内对象、空 object / array、safe integer 边界、float/decimal/exponent 拒绝、duplicate key 拒绝、大整数字符串化和 digest 输入字段
10. 任何 canonical JSON fixture、算法或 manifest digest 输入字段改动，必须同时通过 protocol self-check、Java SDK contract tests、Python SDK contract tests 和 `apps/agent-runtime` 对 Python SDK 的复用测试
11. Java SDK、Python SDK 和 `agent-runtime` 对同一份 canonical JSON fixture 必须产出 byte-for-byte 相同的 canonical bytes 和 digest；任一链路不一致时 CI 必须失败，不能用单语言 snapshot 覆盖另一语言结果
12. UI schema fixtures 是 schema-driven Web 的硬门禁；fixture、字段名或冲突检测规则变更必须同时通过 protocol self-check、Java SDK contract tests、Python SDK contract tests 和 Web definition renderer tests

## 2.6 Version Compatibility

当前阶段采用严格版本门禁，不做单个 Core stack 内的多版本兼容运行。

这是当前阶段的有意约束：Core 只支持一个 extension protocol 主版本，不提供 N -> N+1
双版本运行窗口，也不引入旧版本只读 / readiness warning 过渡机制。升级 extension protocol
major version 时，不做原地滚动升级；必须部署一套新的 Core + runtime + extension stack，
再从 API 入口切换流量。

### 2.6.1 extensionApiVersion

`extensionApiVersion` 是 Extension Plane protocol major version，不是某个 extension service
自身的业务版本，也不是 connector / provider definition version。

1. 类型为整数，例如 `1`
2. Core 当前只支持一个 `extensionApiVersion`
3. service manifest 的 `extensionApiVersion` 不等于 core 支持版本时，manifest validation failed
4. 不做 runtime negotiation
5. 不同时支持多个 extension protocol 主版本
6. 只有破坏性协议变更才提升该版本，例如 `1 -> 2`

### 2.6.2 coreMinVersion / coreMaxVersion

`coreMinVersion` / `coreMaxVersion` 是 extension 声明的 core 运行范围：

```json
{
  "extensionApiVersion": 1,
  "coreMinVersion": "0.8.0",
  "coreMaxVersion": "0.9.x"
}
```

版本字符串规则：

1. 形如 `MAJOR.MINOR.PATCH`，三段都是非负整数
2. `MAJOR.MINOR.x` 表示该 minor 下任意 patch（语义上等价于 `<= MAJOR.MINOR.∞`）
3. `MAJOR.x` 表示该 major 下任意 minor / patch
4. `x` 只允许出现在 trailing 位置；`MAJOR.x.PATCH` 不合法

校验规则：

```text
coreVersion >= coreMinVersion
coreVersion <= coreMaxVersion
```

比较语义按 semver precedence。`coreMinVersion` 不允许使用 `x`（必须给出确切下界）。

不满足时，manifest validation failed，发现问题的 Core 服务启动失败并退出进程。

Descriptor 级版本规则：

1. 当前阶段不引入 `descriptorRevision`、descriptor-level `coreMinVersion` 或 per-descriptor capability version
2. 一个 service manifest envelope 内所有 descriptor 共享 `extensionApiVersion` / `coreMinVersion` / `coreMaxVersion`
3. 如果某个 connector / provider 需要不兼容升级，必须按 service envelope 版本门禁整体升级该 extension service
4. 细粒度 connector API 版本属于 provider-specific 非敏感 config 或 operation mapping 语义，不参与 Extension Plane 协议兼容判定
5. 如未来需要同一 service 内多 descriptor 独立演进，必须先修改 service manifest schema 和 registry validation 规则，而不是在 descriptor 中私自增加版本字段

### 2.6.3 兼容变更规则

同一个 `extensionApiVersion` 内只允许非破坏性变更：

1. 新增 optional 字段
2. 新增 optional endpoint capability
3. 新增 metadata 字段
4. 新增 schema 中非 required 配置

以下变更必须提升 `extensionApiVersion`：

1. 删除字段
2. 修改字段语义
3. required 字段变化
4. enum 值语义变化
5. request / response envelope 结构变化
6. error model 结构变化

### 2.6.4 SDK Version

Java / Python SDK version 不作为跨语言兼容权威：

1. 跨语言兼容权威是 `extensionApiVersion` + OpenAPI / JSON Schema
2. SDK 版本只表达对应语言实现包版本
3. Java / Python SDK major 与 `extensionApiVersion` 对齐，例如 SDK `1.x` 对应 `extensionApiVersion = 1`

### 2.6.5 Validation Failure

版本不兼容使用 registry validation error code：

```text
EXTENSION_API_VERSION_INCOMPATIBLE
```

`details` 字段必填，且至少包含下列字段：

```json
{
  "coreVersion": "0.8.3",
  "supportedExtensionApiVersion": 1,
  "manifestExtensionApiVersion": 2,
  "coreMinVersion": "0.9.0",
  "coreMaxVersion": "0.9.x"
}
```

必填字段为 `coreVersion`、`supportedExtensionApiVersion`、`manifestExtensionApiVersion`、`coreMinVersion`、`coreMaxVersion`。如果某个值因为 manifest schema 损坏无法解析，字段仍必须出现，值使用 `null`，并由同一个 error item 的 `message` 说明解析失败。contract tests 必须覆盖字段存在性和非敏感约束。

## 2.7 SDK Release and Versioning

`packages/extension-sdk-jvm` 是 extension protocol 的 JVM 发布物，`packages/extension-sdk-python` 是 extension protocol 的 Python 发布物。两者共享同一份 `packages/extension-protocol` 事实源。

Java 发布坐标：

```text
group: com.lynxus
artifact: lynxus-extension-sdk-jvm
version: <sdkVersion>
```

Python 发布坐标：

```text
distribution: lynxus-extension-sdk-python
import package: lynxus_extension_sdk
version: <sdkVersion>
```

版本规则：

1. Java / Python SDK major 与 `extensionApiVersion` 对齐
2. `extensionApiVersion = 1` 对应 SDK `1.x.y`
3. SDK minor / patch 只表达对应语言包自身的非破坏性能力、bug fix 或 helper 改进
4. 跨语言协议兼容权威仍然是 `extensionApiVersion`、OpenAPI 和 JSON Schema
5. SDK version 不作为 remote extension protocol 的独立兼容判定依据

发布位置：

1. Java SDK 当前阶段先发布到内部 Maven repository / GitHub Packages
2. Python SDK 当前阶段先发布到内部 Python package registry / GitHub Packages
3. Maven Central / PyPI 是否发布后续单独决策

发布门禁：

1. protocol self-check 通过
2. Java SDK contract tests 通过
3. Python SDK contract tests 通过
4. reference extension contract tests 通过
5. 生成任务可重复执行且产物只落在构建目录，不向源目录产生未提交 diff
6. SDK artifact 不包含 core app 内部实现依赖
