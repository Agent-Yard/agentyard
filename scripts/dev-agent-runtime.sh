#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_RUNTIME_DIR="$ROOT_DIR/apps/agent-runtime"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/agent-runtime"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv was not found. Install uv first, then run 'uv sync --all-packages' at $ROOT_DIR." >&2
  exit 1
fi
HOST="${LYNXUS_AGENT_RUNTIME_HOST:-0.0.0.0}"
PORT="${LYNXUS_AGENT_RUNTIME_PORT:-8090}"

cd "$AGENT_RUNTIME_DIR"
exec uv run --package lynxus-agent-runtime uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
