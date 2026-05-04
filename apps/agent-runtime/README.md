# agent-runtime

`agent-runtime` 是 Lynxus 的 Python 执行运行时，负责执行单个 owner agent 的单轮推理。

当前职责包括：

- 接收 `AgentTurnRequest`
- 组装 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities`
- 在单轮推理内执行有上限的 `LLM -> tool_call -> tool_result -> final_decision` 循环
- 返回新的 `sharedState` 快照
- 采集单轮内各次 OpenAI-compatible 模型调用的 usage 明细，并随结果回传给 worker 落库
- 执行 playbook `TOOL_TASK`，为 worker 返回结构化工具任务结果
- 通过内部鉴权和 `traceparent` 头保持服务间调用约束与链路日志

## 启动

以下命令默认在仓库根目录执行：

```bash
cp .env.example .env
uv sync --all-packages
pnpm local:agent-runtime
```

也可以直接运行该服务或单测：

```bash
uv run --package lynxus-agent-runtime uvicorn lynxus_agent_runtime.main:app --reload --host 127.0.0.1 --port 8090
uv run --directory apps/agent-runtime --package lynxus-agent-runtime pytest tests/test_internal_auth.py
```

[`scripts/local/agent-runtime.sh`](/Users/eric/projects/lynxus/scripts/local/agent-runtime.sh) 会直接使用 `uv run --package lynxus-agent-runtime ...`，因此需要先安装 `uv` 并在仓库根目录执行 `uv sync --all-packages`。

## 接口

当前 runtime 暴露的核心接口：

- `POST /agent-turns/execute-stream`
- `POST /playbook-tool-tasks/execute`

它们都由 `apps/worker` 通过 HTTP 调用，不直接面向控制台页面。

## 环境变量

运行时会通过脚本自动加载根目录 `.env` / `.env.local`，以及 `apps/agent-runtime/.env` / `.env.local`。

本地排查模型调用时，把 `LYNXUS_AGENT_RUNTIME_LOG_LEVEL=DEBUG` 写入 `.env.local`；runtime 会输出每次 OpenAI-compatible LLM 调用的请求 URL、headers（密钥脱敏）、原始 payload、响应状态和原始响应 body。

Extension registration 使用和 API / `channel-gateway` 相同的输入：

- `LYNXUS_EXTENSION_REGISTRATION_FILE`
- `LYNXUS_CHANNEL_GATEWAY_BASE_URL`
- `LYNXUS_AGENT_RUNTIME_BASE_URL`

Transcript store 使用 agent-runtime 独立数据库，表位于默认 `public` schema：

- `LYNXUS_AGENT_RUNTIME_DATABASE_URL`
- `LYNXUS_AGENT_RUNTIME_TURN_EXECUTION_RETENTION_SECONDS`
- `LYNXUS_AGENT_RUNTIME_TRANSCRIPT_ENTRY_RETENTION_SECONDS`
- `LYNXUS_AGENT_RUNTIME_RETENTION_SWEEP_LIMIT`
- `LYNXUS_AGENT_RUNTIME_TRANSCRIPT_CACHE_TTL_SECONDS`

模型资源常用的密钥变量包括：

- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `GEMINI_API_KEY`
- `OPENAI_COMPATIBLE_API_KEY`

如果要接 OpenAI-compatible 网关，还可以配置：

- `LYNXUS_OPENAI_COMPATIBLE_BASE_URL`
- `LYNXUS_OPENAI_COMPATIBLE_MODEL_ID`
- `LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR`
- `LYNXUS_OPENAI_COMPATIBLE_ORGANIZATION`
- `LYNXUS_OPENAI_COMPATIBLE_PROJECT`
- `LYNXUS_OPENAI_COMPATIBLE_REGION`

## 当前边界

- runtime 内部先维护语义层消息 / tool 定义 / tool result，再渲染到 OpenAI-compatible 协议
- 当前 act loop 已接入 OpenAI-compatible function/tool calling
- 模型 usage 由 runtime 采集，worker 负责补齐业务上下文后落库；当前尚无展示接口
- `AgentTurnRequest` 现在会携带 assistant release 冻结后的 model / skill / tool descriptor
- `AgentTurnRequest` 也会携带冻结后的 knowledge binding；runtime 通过内部接口远程调用 knowledge-service 完成在线检索
- tools 会以模型原生 function/tool definitions 暴露，并通过 Tool Connector registry 执行冻结后的 connector descriptor
- skills 采用“目录先暴露，详情按需读取”的模式，模型可先返回 `skillReads` 请求具体 skill prompt
- 未配置可用模型 provider 或执行失败时，runtime 直接返回错误，由 session workflow 统一走 `AGENT_TURN_FAILED` 降级链路
