#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/web"

cd "$ROOT_DIR"
exec pnpm --filter @lynxus/web dev --host "${LYNXUS_WEB_HOST:-0.0.0.0}" --port "${LYNXUS_WEB_PORT:-5173}"
