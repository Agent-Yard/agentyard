#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KNOWLEDGE_SERVICE_DIR="$ROOT_DIR/apps/knowledge-service"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/knowledge-service"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv was not found. Install uv first, then run 'uv sync --all-packages' at $ROOT_DIR." >&2
  exit 1
fi
HOST="${LYNXUS_KNOWLEDGE_SERVICE_HOST:-0.0.0.0}"
PORT="${LYNXUS_KNOWLEDGE_SERVICE_PORT:-8091}"

cd "$KNOWLEDGE_SERVICE_DIR"
exec uv run --package lynxus-knowledge-service uvicorn app.main:app --host "$HOST" --port "$PORT" --reload
