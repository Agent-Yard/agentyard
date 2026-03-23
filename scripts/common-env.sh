#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

load_lynxus_env() {
  local app_dir="${1:-}"
  local env_files=(
    "$ROOT_DIR/.env"
    "$ROOT_DIR/.env.local"
  )

  if [[ -n "$app_dir" ]]; then
    env_files+=(
      "$ROOT_DIR/$app_dir/.env"
      "$ROOT_DIR/$app_dir/.env.local"
    )
  fi

  set -a
  local env_file
  for env_file in "${env_files[@]}"; do
    if [[ -f "$env_file" ]]; then
      # shellcheck disable=SC1090
      source "$env_file"
    fi
  done
  set +a
}
