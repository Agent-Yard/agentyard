#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KNOWLEDGE_SERVICE_DIR="$ROOT_DIR/apps/knowledge-service"
ROOT_VENV_PYTHON="$ROOT_DIR/.venv/bin/python"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/knowledge-service"

resolve_python_bin() {
  if [[ -n "${KNOWLEDGE_SERVICE_PYTHON_BIN:-}" ]]; then
    printf '%s\n' "$KNOWLEDGE_SERVICE_PYTHON_BIN"
    return 0
  fi

  if [[ -x "$ROOT_VENV_PYTHON" ]]; then
    printf '%s\n' "$ROOT_VENV_PYTHON"
    return 0
  fi

  if command -v python3 >/dev/null 2>&1; then
    command -v python3
    return 0
  fi

  echo "python3 was not found. Create $ROOT_DIR/.venv or set KNOWLEDGE_SERVICE_PYTHON_BIN." >&2
  exit 1
}

PYTHON_BIN="$(resolve_python_bin)"
HOST="${LYNXUS_KNOWLEDGE_SERVICE_HOST:-0.0.0.0}"
PORT="${LYNXUS_KNOWLEDGE_SERVICE_PORT:-8091}"

cd "$KNOWLEDGE_SERVICE_DIR"
exec "$PYTHON_BIN" -m uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
