# Tool Connector 架构改造计划

## Summary

将 Tool 从“直接 HTTP/MCP provider 配置”改为“业务能力契约 + Connector 执行实现 + Integration Account/Credential”。第一版放在 `agent-runtime` 内执行，新增独立 integration account 基础模型，并内置 `SIMPLE_HTTP`、`BUSINESS_CODE_SECRET_HTTP`、`MCP` 三类 connector。

## Key Changes

- 重构 Tool 配置契约：
  - `ToolOperation` 只保留业务能力：`name / description / inputSchema / outputSchema`。
  - `ToolConfig` 移除 `providerType/authType/http/mcp`，新增 `connector`：
    - `connectorType`: `SIMPLE_HTTP | BUSINESS_CODE_SECRET_HTTP | MCP`
    - `accountId`: 可空；`SIMPLE_HTTP` 可选用于 Bearer header 鉴权；`BUSINESS_CODE_SECRET_HTTP` 必填；`MCP` 暂不使用 account
    - `timeoutSeconds`
    - `retryPolicy`
    - `config`: connector 级配置
    - `operationMappings`: 以 operation name 为 key 的 connector 私有映射。
  - Tool 相关输出字段统一使用 `connectorType`，不再把 Tool 接入称为 provider；LLM/channel 的 `providerType` 不改。

- 新增 Integration Account/Credential：
  - 新表 `integration_account`：`id, connector_type, name, status, config, credential_ciphertext, credential_fingerprint, created_at, updated_at`。
  - 新控制面 API：list/get/create/update integration accounts。
  - API 响应不返回明文 credential，只返回 `credentialConfigured`。
  - create/update 接收 `credential` map；update 中 `credential = null` 表示保留原凭证，非 null 表示替换。
  - 使用 `AGENTYARD_INTEGRATION_CREDENTIAL_ENCRYPTION_KEY` 做 AES-GCM 加密；未配置时拒绝写入带 credential 的 account。

- Runtime 执行链：
  - `SessionRuntimeService` 发布 Tool 时只投影 connector metadata 和 `accountId`，不把明文 secret 放进 session/Temporal history。
  - `agent-runtime` 新增 connector registry：
    - `SimpleHttpConnector`: operation 映射到 `method/path/requestPlacement`，参数进入 query 或 JSON body；可选绑定 `SIMPLE_HTTP` account，从 credential 的 `bearerToken` 注入 Bearer header。
    - `BusinessCodeSecretHttpConnector`: 从 control-plane 内部接口读取 account credential，使用 `businessCode + secretKey` 计算请求字段。
    - `McpConnector`: 承接现有 MCP 调用逻辑。
  - 新增 control-plane internal runtime endpoint：按 `accountId` 返回 decrypted runtime credential，仅 internal token 可访问。
  - `BUSINESS_CODE_SECRET_HTTP` v1 采用稳定 JSON canonicalization + HMAC-SHA256，默认输出字段为 `encrypted`，同时发送 `businessCode`；字段名允许在 connector config 中覆盖。

- UI 和文档：
  - Resource 版本配置页中，仅 TOOL 类型的配置区域从 Provider 配置改为 Connector 配置；LLM_MODEL、SKILL 配置保持现状。Connector 配置：选择 connector type、account、超时、重试、operation mappings。
  - 新增基础 Integration Account 管理入口：列表、创建、编辑、凭证已配置状态；secret 输入不回显。
  - 更新 `docs/architecture` 中 Tool/Connector/Account 三层说明，不修改 `docs/develop_record/`。

## Test Plan

- Java/API：
  - Tool config normalization 使用新 connector 结构，旧 provider 字段不再作为目标模型。
  - Integration account create/update/list/get 验证 secret 加密、响应脱敏、credential update 语义。
  - Session runtime projection 确认 ToolDescriptor 不包含明文 secret，仅包含 connector metadata/accountId。
  - Internal runtime account endpoint 需要 internal token，返回解密后的 runtime credential。

- Python/agent-runtime：
  - `SimpleHttpConnector` 按 operation mapping 调用不同 method/path。
  - `BusinessCodeSecretHttpConnector` 使用 fixture credential 生成稳定 `encrypted` 字段并调用目标接口。
  - MCP connector 保持现有 operation mapping 行为。
  - Tool input/output schema 校验仍在 connector 调用前后执行。

- Frontend：
  - Tool Resource 编辑器能创建三类 connector 配置。
  - Integration Account 表单不回显 secret，更新非 secret 字段不清空 credential。
  - Resource 配置 summary 显示 connector type/account/status，而不是 providerType/authType。

## Assumptions

- 不做旧数据兼容迁移；现有 Tool provider 配置按项目规则直接进入目标架构。
- 第一版不做外部插件加载机制；connector registry 先在 `agent-runtime` 内置，后续插件化只扩展 registry。
- `BUSINESS_CODE_SECRET_HTTP` 只覆盖 common HMAC 类签名/加密字段场景；厂商私有 AES/RSA/复杂登录态后续以专用 connector 实现。
- Cookie/login session 维护先通过 connector context 预留接口，不在本轮实现完整状态机。
