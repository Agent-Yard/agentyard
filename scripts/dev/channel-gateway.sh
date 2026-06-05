#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_NAME="dev"

source "$ROOT_DIR/scripts/common/env.sh"
load_agentyard_env "apps/channel-gateway" "$ENV_NAME"
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-$(default_spring_profile "$ENV_NAME")}"

cd "$ROOT_DIR"
exec ./gradlew --console=plain :apps:channel-gateway:bootRun
