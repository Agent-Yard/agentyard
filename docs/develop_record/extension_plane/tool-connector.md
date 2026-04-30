# Tool Connector Extension

## 1. 目标

Tool 是业务能力契约；Connector 是协议、鉴权、签名、请求编排和远端调用。

`connectorType` 目标形态是 string descriptor id：

```text
simple-http
business-code-secret-http
mcp
enterprise.acme.crm
enterprise.acme.erp
```

目标是让新增 connector 不再需要同时改：

1. Python runtime registry
2. API `ToolConnectorCatalog`
3. Web `toolConnectors.ts`

Tool / Operation 来源：

1. Tool Resource、Tool Operation、input / output schema 和 operation 列表属于 catalog / assistant release 层概念
2. connector manifest 不声明自己暴露哪些 operation，只声明 connector runtime 能力、账号规则、config schema 和 operation mapping schema
3. `operationMappingSchema` 描述 catalog operation 映射到外部系统调用所需的非敏感配置
4. remote request 中的 `operation.name` / `operation.description` 来自 catalog 中当前被调用的 Tool Operation，不来自 connector manifest
5. 同一个 connector 可以被多个 Tool Resource / Operation 复用，差异由 Tool Resource config 与 operation mapping 表达

## 2. Connector Manifest

Connector manifest 是 service-level manifest envelope 中的 tool connector descriptor。built-in reference connector 的 descriptor 由 `agent-runtime` 内部 `DescriptorProvider` 产出，并通过 `agent-runtime` 自身的 `/extension/manifest` endpoint 暴露给 API；remote connector service 的 descriptor 通过自身 `/extension/manifest` 暴露。两类来源的 envelope shape、schema 校验和 digest 计算完全一致。

下方仅展示单个 tool connector descriptor 元素；外层 service-level envelope 字段（`extensionApiVersion` / `coreMinVersion` / `coreMaxVersion` / `descriptors.*`）按 `extension-protocol.md §2.1` 补齐。
Tool Connector definition digest 的 canonical object 字段列表以 `static-registration.md §5.2` 为准。

```json
{
  "connectorType": "enterprise.acme.crm",
  "title": "Acme CRM",
  "accountConfigSchema": {},
  "accountConfigUiSchema": [],
  "configSchema": {},
  "configUiSchema": [],
  "credentialSchema": {},
  "credentialUiSchema": [],
  "operationMappingSchema": {},
  "operationMappingUiSchema": [],
  "endpoints": {
    "invoke": "/tools/invoke",
    "createCredential": "/credentials",
    "rotateCredential": "/credentials/rotate",
    "revokeCredential": "/credentials/revoke",
    "validateCredential": "/credentials/validate"
  }
}
```

规则：

1. `invoke` 是 remote connector 必填 endpoint
2. `title` 是面向 Web 和管理员展示的用户可读名称，用于快速识别 connector；`description` 如存在则只作为补充说明
3. Tool connector descriptor 不默认强制 Integration Account 或 Core-managed credential；connector 可以完全通过 extension 私有配置 / 私有存储维护 credential
4. `credentialSchema` 与 `endpoints.createCredential` / `rotateCredential` / `revokeCredential` / `validateCredential` 是可选能力；一旦声明任一 credential endpoint，就必须同时声明完整四个 endpoint 和 `credentialSchema`
5. built-in reference connector 可以声明 `credentialSchema` / `credentialUiSchema` 但不声明 credential endpoints；这种模式使用 Core-owned encrypted reference secret，沿用现有 API 加密存储和 internal runtime resolver，不属于 remote extension protocol
6. credential endpoint 契约引用 `extension-protocol.md §2.2`，credential 持久化和状态规则引用 `credentials-and-persistence.md`
7. manifest validation 只做轻量 shape 校验：字段存在性、类型、endpoint path 形态、JSON Schema 自身合法性。credential 是否已配置、账号是否可用、schema 与 extension 私有实现是否语义匹配，不在 registry 阶段拦截，由创建 account、发布 release 或 runtime invocation 时报错暴露
8. `configSchema` 描述 Tool Resource connector 级运行配置，`operationMappingSchema` 描述单个 Tool Operation 到外部调用的映射配置；二者与 Integration Account 级的 `accountConfigSchema` 分离，不得互相承载对方字段，也不得包含 secret 字段；secret 边界引用 `extension-protocol.md §2.0`
9. `accountConfigSchema` 描述 Integration Account 的非敏感账号配置（identity / tenant / scope 等），
   不得包含 secret 字段；secret material 只能通过 credential endpoints 或 extension 私有配置 / 私有存储维护
10. Integration Account create / update 时，control-plane API 必须按 `TOOL_CONNECTOR + connectorType`
   查找当前 definition registry，并用该 descriptor 的 `accountConfigSchema` 校验
   `integration_account.config`；Web 侧校验只作为体验优化
11. `accountConfigUiSchema` / `configUiSchema` / `credentialUiSchema` / `operationMappingUiSchema`
   是对应 JSON Schema 的展示 hint，字段引用、冲突检测和 fixture 规则引用 `extension-protocol.md §2.4`
12. `endpoints.invoke` 的 key 固定、path 由 manifest 声明；`/tools/invoke` 是推荐示例，不是协议强制值
13. Tool Resource create / update 必须显式保存 `connector.config` 和每个 operation 的 `operationMappings[operation.name]`；空对象 `{}` 只有在对应 JSON Schema 允许时才有效
14. Web 可以在本地初始化空表单值；API 保存时只接受提交 payload 中的显式配置

## 3. Runtime 调用路径

```text
agent-runtime
  -> connector registry
    -> local built-in connector
    -> remote connector adapter
      -> enterprise connector service {endpoints.invoke}
```

企业 connector 默认 remote sidecar。

Tool connector invocation 分两层：

```text
internal ConnectorCall
  给 agent-runtime 内置 connector 使用
  可以保留 ToolDescriptor / ToolOperationDescriptor / ConnectorRuntime 等内部对象

RemoteToolInvokeRequest
  给 remote extension declared invoke endpoint 使用
  只包含稳定、最小、去内部化字段
```

内置 connector 不需要强制走 remote HTTP DTO。`RemoteToolConnectorAdapter` 负责把 internal `ConnectorCall` 转成 `RemoteToolInvokeRequest`。

## 4. Remote Tool Invoke Request

remote request 字段：

```json
{
  "connectorType": "enterprise.acme.crm",
  "externalSecretRef": "vault://...",
  "tool": {
    "resourceId": "resource-xxx",
    "resourceVersionId": "resource-version-xxx",
    "name": "CRM 查询"
  },
  "operation": {
    "name": "query_customer",
    "description": "查询客户信息"
  },
  "config": {
    "connector": {},
    "operationMapping": {}
  },
  "input": {
    "arguments": {}
  },
  "execution": {
    "idempotencyKey": "tool-call-xxx",
    "timeoutSeconds": 15,
    "traceContext": {
      "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    }
  }
}
```

`externalSecretRef` 字段规则：

1. `externalSecretRef` 可选，只有 assistant release snapshot 中已固化该字段时才发送
2. remote Tool invocation 不发送 Core 内部 `accountId`
3. 若 release snapshot 缺少 `externalSecretRef`，runtime 不直接判定为协议错误；connector 可以依赖 extension 私有配置成功执行，也可以返回业务错误或认证错误

`externalSecretRef` 持久化位置和 `credential_status` 判定规则以
`credentials-and-persistence.md §2.3` / `§6.1` / `§7` 为准。

`tool.resourceVersionId` 仅用于 core / extension 双侧审计与排障关联，不参与 connector 业务逻辑或 secret routing；release snapshot 不允许通过该字段反向恢复 credential 或敏感配置（见 `credentials-and-persistence.md §7`）。

remote request 不传：

1. `inputSchema`
2. `outputSchema`
3. full `ToolDescriptor`
4. full `AgentConfig`
5. assistant release snapshot
6. session / playbook 全量状态
7. 明文 credential
8. Core 内部 `accountId`
9. core 内部 enum 或 Java / Python DTO 结构
10. connector implementation version

Tool connector 与 Channel provider 共用 Integration Account 模型；Tool Resource 可以保存 `accountId`，assistant release snapshot 可以保存 `accountSnapshot.accountId` 与 `accountSnapshot.externalSecretRef`。remote invocation 只使用 `externalSecretRef`；`accountId` 只服务 Core 内部校验和 built-in reference connector 的 internal resolver。

connector implementation version 不进入 runtime request。版本门禁由 service manifest 的 `extensionApiVersion` / `coreMinVersion` / `coreMaxVersion` 和 contract tests 负责。

`config.connector` 和 `config.operationMapping` 不得携带 secret。需要 API key、access token、
private key 或其他 credential material 时，remote connector 必须通过已有的 `externalSecretRef`、
extension 私有配置或 extension 私有存储获取。

`execution.traceContext.traceparent` 必填，由 Core 调用 extension 前生成或透传。`X-Lynxus-Trace-Id`
必须与 `traceContext.traceparent` 属于同一 trace。

## 4.1 Tool Release Materialization Contract

Tool Resource 是 Tool Connector 运行配置的 control-plane 权威，assistant release snapshot 是
agent-runtime 执行时唯一读取的冻结视图。API publish 时物化，runtime 只读 snapshot，不回 API。

现有实现已经有 Tool 配置与 release runtime descriptor 的基本结构，目标改造不新建一套
`toolResources` 外层 envelope，而是沿用并重构现有链路：

```text
ToolConfigDto
  operations: List<ToolOperationDto>
  connector: ToolConnectorConfigDto

assistant release / agent-runtime
  ToolDescriptor
    operations: List<ToolOperationDescriptor>
    connector: ToolConnectorDescriptor
```

Tool Resource / Tool Operation 保存目标沿用现有 `ToolConfigDto` 形态：

```json
{
  "operations": [
    {
      "name": "query_customer",
      "description": "查询客户信息",
      "inputSchema": "{\"type\":\"object\"}",
      "outputSchema": "{\"type\":\"object\"}"
    }
  ],
  "connector": {
    "connectorType": "enterprise.acme.crm",
    "accountId": "integration-account-xxx",
    "timeoutSeconds": 15,
    "retryPolicy": {
      "mode": "NONE",
      "maxAttempts": 1,
      "initialDelayMs": 0,
      "maxDelayMs": 0,
      "backoffMultiplier": 1.0,
      "retryableCategories": [],
      "retryableErrorCodes": []
    },
    "config": {},
    "operationMappings": {
      "query_customer": {}
    }
  }
}
```

字段规则：

1. `connectorType` 是 string descriptor id，不再使用 JVM / TS enum 作为业务事实源
2. `connector.accountId` 可为 `null`；Tool Connector 不默认强制绑定 Integration Account
3. `connector.config` 由 connector definition 的 `configSchema` 校验
4. `connector.operationMappings[operation.name]` 由 connector definition 的 `operationMappingSchema` 校验
5. `connector.config` 与 `operationMappings` 不得包含 secret、credential 明文、`externalSecretRef` 或可还原 secret material
6. `connector.config` 和每个 `operationMappings[operation.name]` 都必须来自 Tool Resource 保存态；API 按提交 payload 持久化并校验
7. retry policy 是 Core-owned Tool Resource connector config 的一部分，不属于 connector manifest，也不透传给 remote connector service
8. 现有 `retryPolicy` 若仍是字符串 preset，进入 release snapshot 前必须解析为结构化 retry policy；runtime retry 判定不得依赖 Web 展示字符串

Assistant release snapshot 沿用现有 `ToolDescriptor` / `ToolConnectorDescriptor` 形态，并把
account 引用扩展为冻结后的 account snapshot：

```json
{
  "resourceId": "resource-xxx",
  "resourceName": "CRM 查询",
  "resourceVersionId": "resource-version-xxx",
  "resourceVersion": "1.0.0",
  "operations": [
    {
      "name": "query_customer",
      "description": "查询客户信息",
      "inputSchema": "{\"type\":\"object\"}",
      "outputSchema": "{\"type\":\"object\"}"
    }
  ],
  "connector": {
    "connectorType": "enterprise.acme.crm",
    "accountSnapshot": {
        "accountId": "integration-account-xxx",
        "externalSecretRef": "vault://..."
    },
    "timeoutSeconds": 15,
    "retryPolicy": {
      "mode": "NONE",
      "maxAttempts": 1,
      "initialDelayMs": 0,
      "maxDelayMs": 0,
      "backoffMultiplier": 1.0,
      "retryableCategories": [],
      "retryableErrorCodes": []
    },
    "config": {},
    "operationMappings": {
      "query_customer": {}
    }
  }
}
```

Assistant release snapshot 的 account 引用固定为 `connector.accountSnapshot`。API publish 从 Tool Resource 的
`connector.accountId` 读取管理员选择的 Integration Account，并物化为
`accountSnapshot.accountId`；agent-runtime 只读取 release snapshot，不回 API 查询。

Materialization rules：

1. 发布 assistant release 时，API 从当前 Tool Resource / Tool Operation 读取 `connector.connectorType`、`connector.accountId`、`connector.config`、retry policy 和 `connector.operationMappings`
2. API 必须确认 `connectorType` 存在于当前 Tool Connector definition registry
3. API 使用当前 connector definition 校验 `connector.config` 和每个 `operationMappings[operation.name]`
4. 每个 Tool Operation 都必须存在显式保存的 `operationMappings[operation.name]`；发布前按 schema 完整校验
5. 如果选择了 `connector.accountId`，API 必须校验 account `subjectType = TOOL_CONNECTOR`、`subjectId = connectorType`、`status = ENABLED`
6. `REVOKE_FAILED` / `REVOKED` 的 account 不允许生成新的 release snapshot
7. API 不要求 `credentialStatus = ACTIVE` 才能发布；`NOT_CONFIGURED`、`VALIDATION_FAILED`、`ROTATION_REQUIRED` 等状态只作为风险提示
8. API 只在 account 当前已有 `externalSecretRef` 时固化到 `accountSnapshot.externalSecretRef`
9. 未选择 account 时，`connector.accountSnapshot` 为 `null`；选择 account 但没有 `externalSecretRef` 时，`accountSnapshot.accountId` 有值且 `externalSecretRef` 为空
10. release snapshot 不保存 credential 明文，不保存 connector endpoint path、registration `baseUrl` 或 internal token

Agent-runtime rules：

1. `agent-runtime` 只从 assistant release snapshot 读取 tool connector runtime config
2. remote connector invocation 不回 API 读取 Integration Account、credential status、`externalSecretRef` 或 connector definition
3. built-in reference connector 使用 Core-owned encrypted reference secret 时，`agent-runtime` 可以按 snapshot 中的 `accountSnapshot.accountId` 调 API internal runtime credential resolver 获取明文 credential；该路径只服务 in-process connector，不进入 remote extension request
4. 构造 `RemoteToolInvokeRequest` 时，只有 `accountSnapshot.externalSecretRef` 存在才发送顶层 `externalSecretRef`
5. `RemoteToolInvokeRequest` 不发送 `accountSnapshot.accountId`
6. `RemoteToolInvokeRequest.config.connector` 来自 snapshot 的 `connector.config`
7. `RemoteToolInvokeRequest.config.operationMapping` 来自当前 operation 的 snapshot `connector.operationMappings[operation.name]`
8. `RemoteToolInvokeRequest.operation.name` / `description` 来自 snapshot 中当前 Tool Operation，不来自 connector manifest
9. output schema 校验使用 snapshot 中当前 Tool Operation 的 `outputSchema`
10. snapshot 缺少 `externalSecretRef` 不属于 core runtime 协议错误；extension 可以依赖私有配置成功，也可以返回业务或认证错误

Credential change after release：

1. credential rotate 必须保持同一个 `externalSecretRef` 稳定；已有 snapshot 通常不需要重发 release
2. 如果旧 snapshot 没有 `externalSecretRef`，后续 credential create / rotate 不会自动把 ref 写入旧 snapshot；需要重新发布 release 才能获得新 ref
3. credential revoke 后 extension 必须使旧 `externalSecretRef` 不再可解析；持有旧 snapshot 的 runtime invocation 会在 extension 边界失败
4. API 不因 credential 状态变化自动回收、重写或重发已有 assistant release snapshot
5. 新 release 必须按发布时的 account 状态重新执行 materialization rules

## 5. Remote Tool Invoke Response

2xx response 使用 envelope。2xx 只表示成功，不承载业务失败：

```json
{
  "status": "SUCCEEDED",
  "output": {},
  "metadata": {}
}
```

约束：

1. `status` 固定为 `SUCCEEDED`
2. 不保留 `FAILED` 作为 2xx response status
3. `output` 是业务 tool result，必须是 JSON object
4. Agent / Playbook 只消费 `output`
5. `metadata` 不参与 output schema 校验，不进入 Agent 可见 tool result；只允许非敏感小字段，原始响应 / 大 payload 由 extension 自己写日志保留

非 2xx response 使用 `extension-protocol.md §2.3` 定义的 `ExtensionError`，由 core runtime 映射成内部 `ToolExecutionFailure`。

如果 remote connector 返回 2xx 且 `status != SUCCEEDED`，core runtime 视为 `EXTENSION_PROTOCOL_ERROR`。

## 6. Output Schema 校验责任

core runtime 是最终 output schema 校验权威。

```text
agent-runtime
  -> RemoteToolConnectorAdapter
    -> POST {connector.endpoints.invoke}
    <- ToolInvokeResponse
  -> validate response envelope
  -> require output is JSON object
  -> validate output against ToolOperation.outputSchema
  -> return output to agent/playbook
```

extension 可以自检，但不是 schema 判定权威。remote request 不传 `outputSchema`。

校验失败映射：

```text
envelope 不合法
  -> EXTENSION_PROTOCOL_ERROR
  -> HTTP status 502
  -> audit eventType TOOL_CONNECTOR_PROTOCOL_ERROR

output 不是 JSON object
  -> TOOL_OUTPUT_INVALID
  -> HTTP status 422
  -> audit eventType TOOL_OUTPUT_VALIDATION_FAILED

outputSchema 校验失败
  -> TOOL_OUTPUT_SCHEMA_VIOLATION
  -> HTTP status 422
  -> audit eventType TOOL_OUTPUT_SCHEMA_VIOLATION
```

## 7. 错误模型与失败映射

Tool connector 和 Channel provider 共用 `extension-protocol.md §2.3` 的完整 `ExtensionError`
schema 和 category 枚举。本节只定义 Tool 侧业务失败映射，不重新定义错误 shape。

Tool 侧映射：

```text
AUTH
  -> TOOL_CONNECTOR_AUTH_FAILED

BAD_REQUEST
  -> TOOL_ARGUMENT_INVALID or TOOL_CONNECTOR_BAD_REQUEST

REMOTE_TIMEOUT
  -> TOOL_CONNECTOR_TIMEOUT

REMOTE_UNAVAILABLE
  -> TOOL_CONNECTOR_UNAVAILABLE

REMOTE_RATE_LIMITED
  -> TOOL_CONNECTOR_RATE_LIMITED

REMOTE_BUSINESS_REJECTED
  -> TOOL_BUSINESS_REJECTED

PROTOCOL_ERROR
  -> EXTENSION_PROTOCOL_ERROR

CIRCUIT_OPEN
  -> TOOL_CONNECTOR_CIRCUIT_OPEN

UNKNOWN
  -> TOOL_CONNECTOR_UNKNOWN_ERROR
```

Agent tool call 收到 tool execution failure 后，由 agent loop 决定是否继续。Playbook tool task 收到 tool execution failure 后，按 `ExtensionError.retryable` 和 connector retry policy 决定是否重试；不可重试或重试耗尽后进入失败分支或 terminal failure。

### 7.1 Retry 合并规则

重试判定由 core runtime 执行，remote connector 只表达错误是否具备重试可能性。

合并规则：

```text
shouldRetry =
  ExtensionError.retryable == true
  AND connector retry policy enabled
  AND error category / errorCode matches retry policy
  AND attempt count not exhausted
  AND failure is not protocol / output validation failure
```

规则说明：

1. `ExtensionError.retryable = false` 是硬停止，connector retry policy 不能覆盖成可重试
2. `ExtensionError.retryable = true` 只表示允许重试，不表示必须重试
3. connector retry policy 决定最大次数、backoff、允许重试的 `category` / `errorCode`
4. `PROTOCOL_ERROR`、response envelope 不合法、`output` 非 JSON object、output schema 校验失败默认不可重试
5. core runtime 产生的 transport timeout / connection refused / 5xx without valid `ExtensionError` 可以映射为 retryable `ExtensionError`
6. 同一个 logical tool invocation 的 retry 必须复用同一个 `execution.idempotencyKey`；字段规则见 `extension-protocol.md §2.0`

默认策略：

1. 没有配置 connector retry policy 时不自动重试
2. Agent tool call 默认不由 connector policy 自动重试，由 agent loop 决定后续行为
3. Playbook tool task 可以按 connector retry policy 自动重试

Connector retry policy 是 Core-owned Tool Resource connector config，不属于 connector manifest，也不透传给 remote connector service。逻辑 schema：

```json
{
  "mode": "NONE",
  "maxAttempts": 1,
  "initialDelayMs": 0,
  "maxDelayMs": 0,
  "backoffMultiplier": 1.0,
  "retryableCategories": [],
  "retryableErrorCodes": []
}
```

规则：

1. `mode` 取值：`NONE` / `FIXED` / `EXPONENTIAL`
2. `maxAttempts` 包含首次调用；`mode = NONE` 时必须为 `1`
3. `retryableCategories` 使用 `extension-protocol.md §2.3` 的 `category` 枚举
4. `retryableErrorCodes` 是 connector-specific allowlist；为空表示只按 `retryableCategories` 判定
5. `PROTOCOL_ERROR`、`EXTENSION_PROTOCOL_ERROR`、`TOOL_OUTPUT_INVALID`、`TOOL_OUTPUT_SCHEMA_VIOLATION` 永远不可被 policy 改成可重试
6. control-plane schema 可以把常用策略保存为命名 preset，但进入 runtime 前必须解析成上述逻辑 schema；runtime retry 判定不得依赖 Web 展示字符串

## 8. Remote Execution Mode

不引入 `REMOTE_HTTP_CONNECTOR` 作为业务 connector 类型。

remote 是 execution mode，不是 Tool 业务层看到的新 connector 语义：

```text
built-in connector
  -> internal ConnectorCall
  -> in-process execution

remote connector
  -> internal ConnectorCall
  -> RemoteToolConnectorAdapter
  -> RemoteToolInvokeRequest
  -> POST {connector.endpoints.invoke}
```

## 9. 内置 Reference Connector

开源 reference connector 由 `agent-runtime` 进程内执行：

1. `simple-http`
2. `business-code-secret-http`
3. `mcp`

它们通过 `agent-runtime` 内部 `DescriptorProvider` 注册，对外通过 `agent-runtime` 自身的 `/extension/manifest` endpoint 暴露给 API。`agent-runtime` 自身加载 `core-agent-runtime` preset registration 时，直接读 `DescriptorProvider`，不发起对自身的 HTTP 调用。

`/extension/manifest` 与 `DescriptorProvider` 共用同一条 canonical JSON 序列化路径，确保 API 通过 HTTP 拉到的 descriptor digest 与 `agent-runtime` 本地计算的 digest 完全一致。

内置 reference connector 没有独立 extension 私有存储时，使用 Core-owned encrypted reference secret 和 internal credential resolver 获取明文 credential。该路径沿用现有 API 加密存储实现，只服务 core in-process reference implementation，不属于 remote Tool Connector invocation protocol，也不生成 `externalSecretRef`。

`agent-runtime` 是 Python 实现，必须复用 `packages/extension-sdk-python` 提供的 manifest envelope 序列化、JSON Schema 校验、`ExtensionError` 解析和 canonical JSON 实现。Python SDK 与 Java SDK 由同一份 `packages/extension-protocol` 的 OpenAPI / JSON Schema / canonical JSON fixtures 驱动，`agent-runtime` 不允许再维护一套只服务自身的协议实现。
