#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

source "$ROOT_DIR/scripts/common/env.sh"
load_lynxus_env "apps/channel-gateway"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"

cd "$ROOT_DIR"
exec ./gradlew --console=plain :apps:channel-gateway:bootRun
