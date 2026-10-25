# Tool Connector 开发

核对日期：2026-10-04。本文说明当前连接器实现；目标的实例身份与工具授权改造见[协作架构](agent-collaboration.md)。协议字段以 [extension-protocol](../../packages/extension-protocol/README.md) 为准。

## 1. 三个边界

Tool operation 定义业务输入输出，Tool Connector 处理调用协议，Integration Account 管理账号与凭证引用。Agent 只选择业务 operation 并提交参数，不负责拼接密钥、签名、Cookie 或厂商认证。

当前内置 SIMPLE_HTTP、BUSINESS_CODE_SECRET_HTTP、MCP，也支持远程扩展。connectorType 是注册的 descriptor ID，不是只能添加固定枚举值的封闭类型。

| 部分 | 当前实现入口 |
| --- | --- |
| Runtime 分发与内置执行 | [tool_connectors.py](../../apps/agent-runtime/agentyard_agent_runtime/tool_connectors.py) |
| Manifest 加载与 registry | [extension_registry.py](../../apps/agent-runtime/agentyard_agent_runtime/extension_registry.py) |
| 控制面聚合定义 | [ExtensionDefinitionService.java](../../apps/api/src/main/java/com/agentyard/platform/extension/ExtensionDefinitionService.java) |
| 资源配置校验 | [CatalogService.java](../../apps/api/src/main/java/com/agentyard/platform/catalog/CatalogService.java) |
| Web 通用表单 | [toolConnectorDefinitionForms.ts](../../apps/web/src/components/toolConnectorDefinitionForms.ts)、[integrationAccountDefinition.ts](../../apps/web/src/pages/integrationAccountDefinition.ts) |
| 运行描述符 | [models.py](../../apps/agent-runtime/agentyard_agent_runtime/models.py)、[JVM RuntimeContracts](../../packages/contracts-jvm/src/main/java/com/agentyard/contracts/runtime/RuntimeContracts.java) |

`call_connector_tool` 首先验证 registry 中存在该 connectorType。内置实现从 CONNECTOR_REGISTRY 选取，其余由 RemoteToolConnectorAdapter 根据 manifest 调用远端服务。控制面和前端消费 descriptor/schema，不需要为每个远端厂商增加一套静态表单分支。

## 2. 新增远程 Connector

1. 实现独立 extension service，提供 `GET /extension/manifest` 和 descriptor 声明的 invoke endpoint。
2. 按业务能力设计 operation 的输入输出；工具参数与连接器协议配置分开。
3. 在 manifest 声明账号、凭证、连接器配置和 operation mapping 的 schema，按需声明 UI schema。
4. 通过部署方的扩展注册配置接入；API 与 Runtime 加载并校验 registry。
5. 在控制面配置 Integration Account 与工具资源版本，通过真实 Runtime 调用验证成功、认证失败、参数错误、超时和业务拒绝。
6. 若调整公共协议，同时更新 OpenAPI、JSON Schema、examples、fixtures、SDK 与消费者。

完整 manifest、认证头、注册方式和 credential lifecycle 见[Extension 接入](extension-integration.md)。扩展同一 SDK/协议时复用共享 helper，不重复签名、canonical JSON 或 manifest 校验实现。

## 3. 配置职责

| Manifest 字段 | 保存什么 |
| --- | --- |
| accountConfigSchema / accountConfigUiSchema | 可复用账号的非敏感配置 |
| credentialSchema / credentialUiSchema | 凭证输入，敏感字段标注 secret |
| credentialLifecycleEndpointProfiles | 可选的创建、轮换、撤销、验证端点，由 descriptor 引用 |
| configSchema / configUiSchema / defaultConfig | 具体工具的连接器配置与非敏感默认值 |
| operationMappingSchema / operationMappingUiSchema | 业务 operation 到远端协议的映射配置 |

运行时 ToolConnectorDescriptor 包含 connectorType、accountSnapshot、timeoutSeconds、retryPolicy、config、operationMappings。accountSnapshot 携带账号和密钥引用，不在模型参数或 Task 共享上下文中公开实际凭证。

retryPolicy 是结构化对象，mode 为 NONE / FIXED / EXPONENTIAL，包含 maxAttempts、延迟、退避、可重试类别与错误码。NONE 要求 maxAttempts 为 1。远程适配器已经实现有条件重试；是否启用还受调用上下文的 retry_enabled 约束，不能将“配置了策略”理解为所有入口都会自动重试。可能产生副作用的调用应按回执与幂等语义处理。

## 4. 修改内置实现

只有改变内置能力时才修改 Runtime 内置 adapter/registry 及对应 descriptor。先使用现有通用 schema 表单；只有能力本身无法表达时才扩充共享 UI/协议，不为厂商重复维护近似实现。

角色模板固定引用已发布的工具版本，Session 按该版本解析运行描述符。调用前，工具执行边界会重新校验实例的有效授权、工具操作与接入账号状态。角色模板引用工具不等于授予账号或对象访问权限。

Agent 工具授权需要在执行时绑定真实实例、操作和目标对象。Tool Connector 的协议认证与应用内对象授权各有作用；连接器能连通不表示 Agent 有权调用任意操作。流程子工具、自动资源读取和后台调用同样遵循这个边界。

## 5. 验证入口

协议或 manifest 变化时：

```bash
pnpm --filter @agentyard/extension-protocol self-check
```

实现变化按影响范围运行对应测试，例如：

```bash
uv run --directory apps/agent-runtime --package agentyard-agent-runtime pytest tests
./gradlew :apps:api:test :apps:worker:test
pnpm test:web
```

确定性测试之外，用实际测试服务和获授权账号验证协议交互；记录未验证的外部能力。本次仅更新文档，没有执行上述实现测试。
