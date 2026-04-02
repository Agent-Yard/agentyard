# agent-runtime

`agent-runtime` 是 Lynxus 的 Python 执行运行时，负责按发布快照执行助手图。

当前职责包括：

- 执行 `START / AGENT / HUMAN / END` 图节点
- 调用知识库、Tool、LLM Model 和 Skill 资源
- 在人工节点生成 checkpoint，并在恢复后继续推进
- 返回 workflow 当前结果，包括节点轨迹、Tool 调用摘要和人工待办

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
uv run --directory apps/agent-runtime --package lynxus-agent-runtime pytest tests/test_memory_prompt.py
```

[`scripts/local/agent-runtime.sh`](/Users/eric/projects/lynxus/scripts/local/agent-runtime.sh) 会直接使用 `uv run --package lynxus-agent-runtime ...`，因此需要先安装 `uv` 并在仓库根目录执行 `uv sync --all-packages`。

## 接口

当前 runtime 暴露两个主要接口：

- `POST /agent-runs/start`
- `POST /agent-runs/resume`

它们由 `apps/worker` 通过 HTTP 调用，不直接面向控制台页面。

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

- 真实模型调用没有本地 fallback
- 当前 Skill 资源承担“按需技能提示读取”职责，不再是独立 Prompt Template 资源
