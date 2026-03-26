# agent-runtime

`agent-runtime` 是 Lynxus 的 Python 执行运行时，负责按发布快照执行助手图。

当前职责包括：

- 执行 `START / AGENT / HUMAN / END` 图节点
- 调用知识库、Tool、LLM Model 和 Skill 资源
- 在人工节点生成 checkpoint，并在恢复后继续推进
- 返回 workflow 当前结果，包括节点轨迹、Tool 调用摘要和人工待办

## 启动

```bash
cp .env.example .env
cd apps/agent-runtime
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cd ../..
pnpm dev:agent-runtime
```

`scripts/dev-agent-runtime.sh` 的 Python 选择顺序为：

1. `AGENT_RUNTIME_PYTHON_BIN`
2. `apps/agent-runtime/.venv/bin/python`
3. 系统 `python3`

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

- 仍保留 `demo.local` 的 HTTP / MCP provider 演示闭环
- 真实模型调用没有本地 fallback
- 当前 Skill 资源承担“按需技能提示读取”职责，不再是独立 Prompt Template 资源
