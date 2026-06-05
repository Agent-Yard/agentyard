#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/common/env.sh"
load_agentyard_env "apps/web"

cd "$ROOT_DIR"
exec pnpm --filter @agentyard/web dev --host "${AGENTYARD_WEB_HOST:-0.0.0.0}" --port "${AGENTYARD_WEB_PORT:-5173}"
