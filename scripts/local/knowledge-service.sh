#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/common/env.sh"
load_agentyard_env "apps/knowledge-service"
export AGENTYARD_LOG_FORMAT="${AGENTYARD_LOG_FORMAT:-console}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv was not found. Install uv first, then run 'uv sync --all-packages' at $ROOT_DIR." >&2
  exit 1
fi
HOST="${AGENTYARD_KNOWLEDGE_SERVICE_HOST:-127.0.0.1}"
PORT="${AGENTYARD_KNOWLEDGE_SERVICE_PORT:-8091}"

cd "$ROOT_DIR/apps/knowledge-service"
exec uv run --package agentyard-knowledge-service uvicorn agentyard_knowledge_service.main:app --host "$HOST" --port "$PORT" --reload
