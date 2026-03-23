#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AGENT_RUNTIME_DIR="$ROOT_DIR/apps/agent-runtime"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/agent-runtime"

resolve_python_bin() {
  if [[ -n "${AGENT_RUNTIME_PYTHON_BIN:-}" ]]; then
    printf '%s\n' "$AGENT_RUNTIME_PYTHON_BIN"
    return 0
  fi

  if [[ -x "$AGENT_RUNTIME_DIR/.venv/bin/python" ]]; then
    printf '%s\n' "$AGENT_RUNTIME_DIR/.venv/bin/python"
    return 0
  fi

  if command -v python3 >/dev/null 2>&1; then
    command -v python3
    return 0
  fi

  echo "python3 was not found. Create apps/agent-runtime/.venv or set AGENT_RUNTIME_PYTHON_BIN." >&2
  exit 1
}

PYTHON_BIN="$(resolve_python_bin)"
HOST="${LYNXUS_AGENT_RUNTIME_HOST:-0.0.0.0}"
PORT="${LYNXUS_AGENT_RUNTIME_PORT:-8090}"

cd "$AGENT_RUNTIME_DIR"
exec "$PYTHON_BIN" -m uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
