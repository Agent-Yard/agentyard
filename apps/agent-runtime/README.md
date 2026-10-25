# Agent Runtime

Python Runtime 执行与真实 Agent Session 关联的 Activation，负责模型、工具、可恢复 transcript、工作摘要和隐私处理。接待与后台工作使用同一入口。

## 启动

在仓库根目录：

```bash
uv sync --all-packages
pnpm local:agent-runtime
```

启动脚本按项目规则加载 `.env` 与服务环境文件；直接运行 `uvicorn` 时需先自行装载环境：

```bash
uv run --package agentyard-agent-runtime uvicorn agentyard_agent_runtime.main:app --host 127.0.0.1 --port 8090
```

## 运行接口

以下执行/历史接口均要求内部 Bearer 认证，不直接供浏览器调用。

| 接口 | 作用 |
| --- | --- |
| `POST /activations/execute` | 输入 activationId，从控制面读取真实主体、配置与上下文，返回 outcome |
| `POST /activations/execute-stream` | 同一执行链的流式表示 |
| `POST /tool-run-nodes/execute` | 经控制面节点授权后执行流程连接器操作 |
| `GET /sessions/{id}/history` | 校验 Session + participantId 后按序号分页/查询历史 |
| `GET /sessions/{id}/summary` | 读取可追溯的历史摘要 |
| `GET /healthz` | PostgreSQL、Redis 等实际依赖健康状态 |
| `GET /extension/manifest` | 内置连接器能力声明 |

模型不能在请求里任意指定一个 Agent profile 或借其他实例身份执行。协作工具绑定当前 Activation，Task 修改、委派、消息、安排和决定都通过控制面的共享服务。

## 配置

- 模型由已发布角色模板引用的模型资源版本提供：providerType、modelId、baseUrl、apiKeyEnvVar、temperature 等。当前执行协议支持 OPENAI / OPENAI_COMPATIBLE。
- API key 从 apiKeyEnvVar 指定的进程环境变量读取，例如 `DEEPSEEK_API_KEY`。模型资源缺失或密钥未提供时返回明确失败，不生成假响应。
- `AGENTYARD_INTERNAL_AUTH_TOKEN`：内部调用凭证。
- `AGENTYARD_API_BASE_URL`：控制面地址，包含 `/api`。
- `AGENTYARD_AGENT_RUNTIME_DATABASE_URL`：Runtime 独立 PostgreSQL 数据库。
- `AGENTYARD_AGENT_RUNTIME_ACTIVATION_LEASE_SECONDS`：运行租约，默认 120 秒。
- `AGENTYARD_AGENT_RUNTIME_CONTEXT_WINDOW_MESSAGES`：近期上下文窗口，默认 160。
- `AGENTYARD_AGENT_RUNTIME_CONTEXT_MAX_CHARS`：模型输入的字符预算，默认 80000，包含工具 schema；不是 token 数估算。
- `AGENTYARD_AGENT_RUNTIME_TRANSCRIPT_CACHE_TTL_SECONDS`：Redis 缓存时长，默认 3600 秒；过期不会删除 PostgreSQL 历史。
- `AGENTYARD_EXTENSION_REGISTRATION_FILE`：连接器注册文件。
- Redis、日志、知识服务地址等以根环境示例和 `main.py` 实际读取项为准。

模型工具步数由控制面 `AGENTYARD_AGENT_MAX_TOOL_STEPS` 提供。API 的并发容量配置与 Runtime 的租约/缓存分开；持久实例无需常驻进程。

## 上下文与工具边界

`runtime_session_context`、`runtime_activation_execution`、`runtime_transcript_entry`、`runtime_working_memory` 和 `runtime_tool_receipt` 保存实际执行证据。重启、Redis 清空和长期空闲都不清除活跃实例上下文。

工作摘要保留 transcript 来源范围，Session 工作记忆由控制面维护。调用结束只回传本次 memory patch。外部写入先登记回执，不确定的调用不自动当成未发生重做。

自动装配只包含工作概览、内容引用及工具运行摘要。完整 Task 正文用 `task_read` / `resource_read` 按需读取，决定记录独立分页。超大工具结果返回明确的省略标记与 activationId/callId；`tool_result_read` 用 offset/limit 分页读取持久原回执，每次重新检查 Session 历史权限并执行隐私处理。上下文预算仅裁剪本次模型视图，不删除原始记录。当前必需指令和工具 schema 本身超出预算时明确失败。

角色模板仅声明能力。Skill 正文按需授权读取；知识检索、连接器、历史与流程节点在实际调用前核对当前授权。模型只接收操作 schema 与授权内容，连接器凭证不进入模型可见工具说明。

模型必须用 `activation_finish` 明确结束本次执行：COMPLETED、WAITING、BLOCKED，或 CONTINUE 交回调度器自动续执行。普通 assistant 文字只保存为私有执行过程；最终回复与富内容块只选一种，显式消息已送达默认接收人时不再自动发送副本。

临时故障和单段步数预算耗尽可以在同一 Activation 中恢复；transcript、工具回执与累计用量持续保存。未决外部写入不会因换 call ID 而重做。具体重试上限、共享状态查询与提交版本规则见[运行收敛机制](../../docs/architecture/activation-completion.md)。

运行状态与最终内容通过控制面的可信流回调路由；内部模型、工具过程不会因此成为用户消息。控制面用正式消息 ID 关联持久最终结果。

## 验证

```bash
uv run --package agentyard-agent-runtime pytest apps/agent-runtime/tests
```

真实 PostgreSQL/Redis 恢复验证见 `tests/test_runtime_persistence_integration.py`。跨语言与真实模型场景由仓库 `./gradlew :apps:api:taskE2eTest` 组织。真实模型测试使用独立的 `AGENTYARD_E2E_MODEL_ID`、`AGENTYARD_E2E_MODEL_BASE_URL`、`AGENTYARD_E2E_MODEL_API_KEY_ENV_VAR` 配置，凭证通过指定的环境变量读取；完整运行示例见[仓库测试说明](../../README.md#测试)，场景范围见[实施验收基线](../../docs/architecture/collaboration-acceptance.md)。
