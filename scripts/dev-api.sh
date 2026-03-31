#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

source "$ROOT_DIR/scripts/common-env.sh"
load_lynxus_env "apps/api"

cd "$ROOT_DIR"
exec ./gradlew --console=plain :apps:api:bootRun
