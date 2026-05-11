# Tool Connector 开发指导

## 目标边界

Tool Connector 的职责是把 Tool 的业务操作映射到具体接入协议。Tool operation 只描述模型可理解的业务能力；Connector 负责协议、鉴权、签名、请求编排和远端调用；Integration Account 负责账号与凭证状态。

新增或调整 connector 时，不要把厂商协议细节泄漏到 Agent、Playbook、Session 投影或 Tool operation schema。协议差异应收敛在 connector 的三个边界内：

- Runtime 执行：`apps/agent-runtime/lynxus_agent_runtime/tool_connectors.py`
- API 归一化：`apps/api/src/main/java/com/lynxus/platform/catalog/ToolConnectorCatalog.java`
- Web 配置定义：`apps/web/src/config/toolConnectors.ts`

## 当前内置 Connector

- `SIMPLE_HTTP`：按 operation mapping 发送 HTTP 请求；可选绑定 Integration Account，并从 credential 注入 Bearer header。
- `BUSINESS_CODE_SECRET_HTTP`：按 operation mapping 发送 HTTP 请求；必须绑定 Integration Account，并用 `businessCode + secretKey` 生成稳定 HMAC 字段。
- `MCP`：调用 MCP gateway；当前不使用 Integration Account，可按配置决定是否带内部鉴权。

## 新增 Connector 流程

1. 定义 connector 类型

   在共享契约中增加新的 `ToolConnectorType`：

   - `packages/contracts-jvm/src/main/java/com/lynxus/contracts/runtime/WorkflowContracts.java`
   - `packages/contracts/src/index.ts`
   - `packages/contracts/openapi/control-plane.yaml`
   - `apps/web/src/types/catalog.types.ts`

   当前项目不要求兼容旧数据；不要为了旧 provider/config 字段增加迁移型分支。

2. 增加 Runtime 执行实现

   在 `tool_connectors.py` 新增一个实现类，并注册到 `CONNECTOR_REGISTRY`。

   Connector 实现应只依赖 `ConnectorCall`：

   - `descriptor`：当前 Tool 资源版本描述
   - `connector`：冻结后的 connector descriptor
   - `operation`：当前业务操作
   - `arguments`：已经通过 Tool input schema 校验的入参
   - `runtime`：加载 Integration Account 和内部鉴权 header 的运行时能力

   如果需要凭证，必须通过 `runtime.load_integration_account(accountId)` 获取，不要把明文凭证放入 session、Temporal history 或 Tool descriptor。

3. 增加 API 归一化定义

   在 `ToolConnectorCatalog` 增加 `Definition`，明确三类规则：

   - `accountRequirement`：`NONE`、`OPTIONAL` 或 `REQUIRED`
   - `defaultConfig`：connector 级默认配置
   - `defaultOperationMapping`：每个 Tool operation 的默认映射

   `CatalogService` 不应再为具体 connector 增加 `if connectorType == ...` 的默认值分支。新增 connector 的默认值和账号规则必须放进 catalog definition。

4. 增加 Web 配置定义

   在 `toolConnectors.ts` 增加 `ToolConnectorDefinition`：

   - `label`
   - `accountMode`
   - `accountPlaceholder`
   - `configFields`
   - `operationMappingFields`
   - `credentialTemplate`

   Resource 配置页和 Integration Account 页面会根据该定义渲染控件。不要在 Vue 页面里为新增 connector 写散落的 `connectorType === ...` 分支，除非是页面级通用交互无法由字段定义表达。

5. 补测试

   Runtime connector 行为优先补在 `apps/agent-runtime/tests/test_playbook_tool_task.py` 或新的 connector 专用测试中，覆盖：

   - operation mapping 到远端请求的转换
   - account required/optional/none 的规则
   - account 类型不匹配和 inactive account
   - credential 字段缺失
   - response 必须是 JSON object

   API 测试优先覆盖 `CatalogServiceTest` 中 Tool config normalization；Web 至少通过 `pnpm --filter @lynxus/web lint`，复杂字段定义应补组件测试。

## 配置模型约定

Tool resource version 的 connector 配置统一为：

```json
{
  "connectorType": "SIMPLE_HTTP",
  "accountId": null,
  "timeoutSeconds": 15,
  "retryPolicy": "NONE",
  "config": {},
  "operationMappings": {
    "operation_name": {}
  }
}
```

- `config` 存 connector 级配置，例如 `baseUrl`、`connectionUri`、签名字段名。
- `operationMappings` 存 operation 级映射，例如 HTTP method/path、remote MCP tool 名。
- `accountId` 只引用 Integration Account；凭证明文只允许出现在 runtime internal credential endpoint 的响应中。
- `retryPolicy` 当前只作为配置字段保留；实现重试语义时应在 connector runtime 层统一处理，避免每个 connector 重复写请求重试。

## 设计原则

- 从目标协议抽象出最小稳定模型，不要把厂商字段直接扩散到 Tool operation。
- 同类逻辑出现三次前就收敛到 catalog/definition/helper。
- 不做“先兼容旧字段、以后再替换”的过渡式设计；按目标结构直接改。
- 一个 connector 的协议实现应尽量自包含；跨 connector 共享的 HTTP、account 校验、JSON response 校验应放到 `tool_connectors.py` 的公共 helper。
- 文档更新属于改造的一部分。新增 connector 后至少更新本文的内置 connector 列表或新增章节。

## 推荐验证命令

```bash
uv run --package lynxus-agent-runtime pytest apps/agent-runtime/tests -q
./gradlew :apps:api:test --tests com.lynxus.platform.catalog.CatalogServiceTest
pnpm --filter @lynxus/web lint
pnpm --filter @lynxus/web test
```
