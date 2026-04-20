# agent-runtime

`agent-runtime` 是 Lynxus 的 Python 执行运行时，负责执行单个 owner agent 的单轮推理。

当前职责包括：

- 接收 `AgentTurnRequest`
- 组装 `PromptInstruction + PromptRuntimeMessages + PromptCapabilities`
- 在单轮推理内执行有上限的 `LLM -> tool_call -> tool_result -> final_decision` 循环
- 返回新的 `sharedState` 快照
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

- `POST /agent-turns/execute`

它由 `apps/worker` 通过 HTTP 调用，不直接面向控制台页面。

## 环境变量

运行时会通过脚本自动加载根目录 `.env` / `.env.local`，以及 `apps/agent-runtime/.env` / `.env.local`。

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
- `AgentTurnRequest` 现在会携带 assistant release 冻结后的 model / skill / tool descriptor
- `AgentTurnRequest` 也会携带冻结后的 knowledge binding；runtime 通过内部接口远程调用 knowledge-service 完成在线检索
- tools 会以模型原生 function/tool definitions 暴露，并按 HTTP / MCP provider config 执行
- skills 采用“目录先暴露，详情按需读取”的模式，模型可先返回 `skillReads` 请求具体 skill prompt
- 未配置可用模型 provider 时，runtime 仍保留 deterministic fallback 作为降级路径
