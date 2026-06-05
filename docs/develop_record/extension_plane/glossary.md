# Extension Plane 术语表

> 本文集中定义 Extension Plane 文档跨主题使用的高频术语，避免每个新读者
> 在 10 个文档里翻找定义。术语条目按主题分组，每条给出短定义和权威主题
> 文档定位（"see ..."）。语义争议以权威主题文档为准；本文只是入口。

## 1. 服务与部署边界

### core app
开源 core 仓库内的应用：`api` / `worker` / `web` / `agent-runtime` /
`knowledge-service` / `channel-gateway`。
see [overview.md §3](./overview.md)、[repository-and-packaging.md §1](./repository-and-packaging.md)

### core runtime service
本套文档中固定指 `channel-gateway` 与 `agent-runtime` 两个服务。它们既是
core app，也作为 reference descriptor 的 manifest host。`worker` /
`knowledge-service` 不属于本期 core runtime service，不暴露
`/extension/manifest`，也不拥有 extension registry。
see [overview.md §3](./overview.md)

### enterprise extension service
企业私有 repo 部署的 extension 服务，通过统一 manifest 协议接入 core。
see [repository-and-packaging.md §2](./repository-and-packaging.md)

### core preset registration
core 自动注入的两条 registration：`core-channel-gateway` /
`core-agent-runtime`，分别指向本服务自身。operator yaml 不允许声明或重写。
see [static-registration.md §2.0](./static-registration.md)

## 2. 注册与协议契约

### registration / `registrationId`
部署层注册项身份，用于日志、health、readiness、运维定位。一条 registration
指向一个 extension service `baseUrl`，并通过 `exposes` 白名单声明该 service
允许暴露的业务 descriptor。`registrationId` 不是业务身份。
see [static-registration.md §2](./static-registration.md)

### descriptor
extension service manifest 中的业务能力声明单元，按类型分为 channel
provider descriptor 与 tool connector descriptor。一个 service envelope
可以同时返回多个 descriptor。
see [extension-protocol.md §2.1](./extension-protocol.md)

### `providerType` / `connectorType`
descriptor 的业务权威身份字符串（如 `feishu`、`enterprise.acme.crm`），
进入 DB、API DTO、Web 下拉和 runtime dispatch。
see [channel-provider.md §1](./channel-provider.md)、[tool-connector.md §1](./tool-connector.md)

### service-level manifest envelope
extension service 通过 `GET /extension/manifest` 返回的顶层结构，包含
`extensionApiVersion` / `coreMinVersion` / `coreMaxVersion` / `descriptors`。
一个 envelope 内所有 descriptor 共用 envelope 级版本字段。
see [extension-protocol.md §2.1](./extension-protocol.md)

### `DescriptorProvider`
core runtime service 内部的单一 descriptor 数据源。同时驱动两条出口：
对外通过 `/extension/manifest` HTTP endpoint，对内被本服务的 runtime
registry 直接读。两条出口共用同一份 canonical JSON 序列化结果。
see [static-registration.md §2.1](./static-registration.md)

### `ToolConnectorRegistry` / `ChannelProviderRegistry`
runtime owner 内的执行 registry：前者归 `agent-runtime`，后者归
`channel-gateway`。按运行边界分域加载，不共享全局执行 registry。
see [overview.md §3](./overview.md)、[static-registration.md §3](./static-registration.md)

### Extension Definition Registry
API 内的 definition 聚合层，通过 HTTP 拉取每条 registration 的
`/extension/manifest`，给 Web / catalog / channel admin 暴露 definition
endpoint，不承担 runtime invocation。
see [overview.md §3](./overview.md)

### definition
descriptor 经 API 聚合后给 Web / 控制面消费的对外形态，对应
`ChannelProviderDefinition` / `ToolConnectorDefinition` DTO。
see [web-configuration.md §2 §3](./web-configuration.md)

### `definitionDigest` / `descriptorDefinitionDigests`
descriptor definition 的 canonical JSON `sha256`，用于跨服务一致性校验。
不包含 `baseUrl`、token 或任何 secret。
see [static-registration.md §5](./static-registration.md)

### `registrationConfigDigest`
合并后 registration config 的 canonical JSON `sha256`，覆盖 registrationId /
source / 解析并规范化后的 baseUrl / `exposes` / `auth.type`，不包含 token 明文。
`baseUrl` 允许 path prefix；host 小写化，默认端口归一，trailing slash 移除。
API / `agent-runtime` / `channel-gateway` 三服务必须算出同一份 digest，
否则 `REGISTRATION_CONFIG_DIGEST_MISMATCH`。
see [static-registration.md §5](./static-registration.md)

### canonical JSON
Extension Plane 协议事实层定义的规范化 JSON 序列化 helper。当前采用
`AgentYardCanonicalJson`：面向 descriptor / registration digest 的受限 profile，
覆盖 key 字典序、字符串转义、UTF-8、安全整数、空容器形态等。Java SDK /
Python SDK / `agent-runtime` 必须对同一输入产出 byte-for-byte 相同的输出。
see [static-registration.md §5](./static-registration.md)、[extension-protocol.md §2.5](./extension-protocol.md)

## 3. 版本与兼容

### `extensionApiVersion`
Extension Plane protocol major version（整数），不是某个 extension service
自身的业务版本。Core 当前只支持一个 `extensionApiVersion`。
see [extension-protocol.md §2.6](./extension-protocol.md)

### `coreMinVersion` / `coreMaxVersion`
extension 在 manifest envelope 中声明的 core 兼容范围。`coreVersion` 必须
落在该范围内，否则 manifest validation failed。
see [extension-protocol.md §2.6.2](./extension-protocol.md)

## 4. 凭证与账号

### Integration Account / `integration_account`
Channel 与 Tool 共用的可选统一账号 / 凭证主身份。承载 `subject_type` /
`subject_id` 两类身份维度，以及可选 `external_secret_ref` 和
`credential_status`。descriptor 不默认强制
Integration Account；管理员可按具体 provider / connector 需要选择使用。
see [credentials-and-persistence.md §3](./credentials-and-persistence.md)

### `externalSecretRef`
由 extension credential create / rotate 返回的 opaque string，是 core 与
extension 之间可选流转的"密钥句柄"。未通过 Core credential lifecycle 管理
credential 时可以不存在。core 不解析其内部结构；长度
≤ 512 字符；不允许进入普通日志、metrics、runtime event、平台事件、
审计 diff 或导出文件。
see [extension-protocol.md §2.0](./extension-protocol.md)、[credentials-and-persistence.md §2.3](./credentials-and-persistence.md)

### credential lifecycle
descriptor 可选暴露的固定常量密钥 create / rotate / revoke / validate 四个动作。
只有 descriptor 声明 credential endpoints 时，Core API 才是 invocation owner，
并写回 `integration_account` 的状态。Core 不建模 access token refresh 或后台 status sync。
see [extension-protocol.md §2.2](./extension-protocol.md)、[credentials-and-persistence.md §6](./credentials-and-persistence.md)

### Core-owned encrypted reference secret
core runtime service 暴露的 built-in reference descriptor 在没有独立 extension
私有存储时使用的本地加密 credential 存储模式。API 将 credential JSON 加密写入
`integration_account.credential_ciphertext`，保存 `credential_fingerprint` 用于变更检测；
runtime owner 通过 internal credential resolver 按 `accountId` 取明文 credential。
该模式不属于 remote extension protocol，不生成 `externalSecretRef`。
see [credentials-and-persistence.md §2.2](./credentials-and-persistence.md)

### `credential_status`
Core-managed credential material 的状态机：`NOT_CONFIGURED` / `ACTIVE` /
`VALIDATION_FAILED` / `ROTATION_REQUIRED` / `REVOKE_FAILED` / `REVOKED`。
credential lifecycle 调用是同步动作，执行中只由 account 级短时排他锁表示，不持久化 pending 状态。
see [credentials-and-persistence.md §6.1](./credentials-and-persistence.md)

### internal credential resolver
core 内 reference connector / gateway-native provider 在没有独立 extension
私有存储时使用的 in-process credential 取值路径。仅服务 core in-process
reference implementation，不属于 remote extension protocol。
see [credentials-and-persistence.md §2.2](./credentials-and-persistence.md)

## 5. Channel 模型

### gateway-native provider
运行在 `channel-gateway` 进程内的 channel provider 实现，webhook 直接
进入 gateway，verify / normalize 在进程内完成。
see [channel-provider.md §2 §4.1](./channel-provider.md)

### remote provider service
运行在企业私有服务内的 channel provider，自己接 webhook，再通过
`/internal/channel-events/normalized` 把标准事件推给 `channel-gateway`。
see [channel-provider.md §2 §4.2](./channel-provider.md)

### `channel_profile`
channel runtime profile，由管理员通过 Web / API 创建的 channel provider
具体接入实例。可承载 Integration Account 引用、provider-specific config、
assistant_binding、inbound 开关。第一版直接重命名自旧
`channel_account`。
see [channel-provider.md §3.0.1 §8](./channel-provider.md)

### `assistant_binding`
`channel_profile` 上的 assistant / scenario 路由配置，使用平台固定
schema，不接受 provider 自定义 DSL 或脚本。
see [channel-provider.md §4.3](./channel-provider.md)

### `NormalizedChannelInboundEvent`
inbound 事件的标准化 DTO，gateway-native provider 与 remote provider
service 最终都必须产出同一个 envelope，由 `channel-gateway` 统一处理
dedup / binding / event store / session-runtime 衔接。
see [channel-provider.md §4.3](./channel-provider.md)

### `channel_profile_template_binding`
assistant 在某个 channel profile 上的外部模板 ID 映射。维度固定为
`assistant_id + channel_profile_id + message_type + message_subtype +
message_version`，不保存外部模板 body；`display_name` /
`external_edit_url` 只用于 Web 展示和管理员跳转。
see [channel-provider.md §8](./channel-provider.md)

### `channel_profile_job` / `channel_profile_job_run`
provider job 配置（包含 schedule_config、cursor、failure_count）与每次
执行历史（status、idempotency_key、events_ingested 等）。`channel_profile_job.status`
是运行态事实源并保留持久 `RUNNING`；write DTO 中的 `scheduleConfig.enabled`
保存时投影成 `ACTIVE / DISABLED`，不进入持久化 `schedule_config`。scanner
通过 Redis lock 和当前 run 超时规则恢复崩溃后的 stale `RUNNING`。
see [channel-provider.md §6 §8](./channel-provider.md)

## 6. Tool 模型

### Tool Resource / Tool Operation
catalog 层概念。Tool Resource 绑定 `connectorType`，并可选绑定 Integration Account；
Tool Operation 描述具体可调用操作及 input / output schema。connector
manifest 不声明自己暴露哪些 operation。
see [tool-connector.md §1](./tool-connector.md)

### `RemoteToolInvokeRequest`
`agent-runtime` 调 remote connector `endpoints.invoke` 的 HTTP envelope。
只包含稳定、最小、去内部化字段，不传 inputSchema / outputSchema /
明文 credential / core 内部 enum；credential handle 只允许使用顶层可选
`externalSecretRef`，不传 `account` 对象或 Core 内部 `accountId`。
see [tool-connector.md §4](./tool-connector.md)

### internal `ConnectorCall`
agent-runtime 内置 connector 使用的内部对象，可保留 ToolDescriptor /
ConnectorRuntime 等内部结构。`RemoteToolConnectorAdapter` 把它转为
`RemoteToolInvokeRequest`。
see [tool-connector.md §3](./tool-connector.md)

## 7. SDK 与包

### `packages/extension-protocol`
协议事实源，保存 OpenAPI、JSON Schema、examples、canonical JSON
fixtures、contract tests。Java SDK / Python SDK 的 DTO / 校验逻辑必须
从这里生成或被 contract tests 约束。
see [extension-protocol.md §1 §2.4](./extension-protocol.md)

### `packages/extension-sdk-jvm` / `packages/extension-sdk-python`
JVM / Python 语言 SDK，提供 DTO、协议常量、错误模型、manifest 校验
入口、HTTP client / server 辅助契约和 contract test 支撑。Java SDK
major 与 `extensionApiVersion` 对齐。
see [extension-protocol.md §1 §2.7](./extension-protocol.md)

## 8. 安全、错误与诊断

### `INTERNAL_TOKEN`
当前唯一支持的 Extension Plane 跨服务鉴权方式。整个 AgentYard 部署共享
一个 token，注入所有 core service 与 enterprise extension service。信任域
等于一次部署域，不提供 extension 间 zero-trust 隔离。
see [deployment-and-governance.md §5](./deployment-and-governance.md)

### `ExtensionError` / `ExtensionErrorCategory`
Tool Connector 与 Channel Provider 共用的错误 envelope 与类别枚举
（`AUTH` / `BAD_REQUEST` / `REMOTE_TIMEOUT` / `REMOTE_UNAVAILABLE` /
`REMOTE_RATE_LIMITED` / `REMOTE_BUSINESS_REJECTED` / `PROTOCOL_ERROR` /
`CIRCUIT_OPEN` / `UNKNOWN`）。
see [extension-protocol.md §2.3](./extension-protocol.md)

### `Idempotency-Key` / `idempotencyKey`
mutating invocation 的幂等键。HTTP header 与 envelope 字段必须一致；
同一 logical operation 的 retry 必须复用同一 key。
see [extension-protocol.md §2.0](./extension-protocol.md)
