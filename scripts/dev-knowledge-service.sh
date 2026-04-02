#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/knowledge-service"
export LYNXUS_LOG_FORMAT="${LYNXUS_LOG_FORMAT:-console}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv was not found. Install uv first, then run 'uv sync --all-packages' at $ROOT_DIR." >&2
  exit 1
fi
HOST="${LYNXUS_KNOWLEDGE_SERVICE_HOST:-127.0.0.1}"
PORT="${LYNXUS_KNOWLEDGE_SERVICE_PORT:-8091}"

cd "$ROOT_DIR/apps/knowledge-service"
exec uv run --package lynxus-knowledge-service uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
