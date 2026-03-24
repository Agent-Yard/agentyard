# agent-runtime

Python execution runtime for Lynxus assistants.
It is responsible for executing the published assistant graph, invoking model / knowledge / skill / MCP resources, and resuming from human checkpoints.

## Start

```bash
cp .env.example .env
cd apps/agent-runtime
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cd ../..
pnpm dev:agent-runtime
```

Environment variables expected by configured model resources come from the root `.env` file:

- `OPENAI_API_KEY`
- `ANTHROPIC_API_KEY`
- `GEMINI_API_KEY`
- `OPENAI_COMPATIBLE_API_KEY`

For an OpenAI-compatible custom model gateway, the root `.env` can also define:

- `LYNXUS_OPENAI_COMPATIBLE_BASE_URL`
- `LYNXUS_OPENAI_COMPATIBLE_MODEL_ID`
- `LYNXUS_OPENAI_COMPATIBLE_API_KEY_ENV_VAR`
