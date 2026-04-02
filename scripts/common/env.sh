#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

load_lynxus_env() {
  local app_dir="${1:-}"
  local environment_name="${2:-}"
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

  if [[ -n "$environment_name" && "$environment_name" != "local" ]]; then
    env_files+=(
      "$ROOT_DIR/.env.$environment_name"
      "$ROOT_DIR/.env.$environment_name.local"
    )

    if [[ -n "$app_dir" ]]; then
      env_files+=(
        "$ROOT_DIR/$app_dir/.env.$environment_name"
        "$ROOT_DIR/$app_dir/.env.$environment_name.local"
      )
    fi
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

default_spring_profile() {
  local environment_name="${1:-local}"
  echo "$environment_name"
}

default_python_log_format() {
  local environment_name="${1:-local}"
  if [[ "$environment_name" == "local" ]]; then
    echo "console"
    return
  fi

  echo "json"
}

default_internal_service_host() {
  local environment_name="${1:-local}"
  if [[ "$environment_name" == "local" ]]; then
    echo "127.0.0.1"
    return
  fi

  echo "0.0.0.0"
}
