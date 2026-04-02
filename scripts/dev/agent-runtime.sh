#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_NAME="dev"

source "$ROOT_DIR/scripts/common/env.sh"
load_lynxus_env "apps/agent-runtime" "$ENV_NAME"
export LYNXUS_LOG_FORMAT="${LYNXUS_LOG_FORMAT:-$(default_python_log_format "$ENV_NAME")}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv was not found. Install uv first, then run 'uv sync --all-packages' at $ROOT_DIR." >&2
  exit 1
fi
HOST="${LYNXUS_AGENT_RUNTIME_HOST:-$(default_internal_service_host "$ENV_NAME")}"
PORT="${LYNXUS_AGENT_RUNTIME_PORT:-8090}"

cd "$ROOT_DIR/apps/agent-runtime"
exec uv run --package lynxus-agent-runtime uvicorn lynxus_agent_runtime.main:app --host "$HOST" --port "$PORT" --reload
