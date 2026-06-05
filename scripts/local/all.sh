#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/agentyard-local.XXXXXX")"

source "$ROOT_DIR/scripts/common/process.sh"

SERVICE_ROOT_PIDS=()
TAIL_ROOT_PIDS=()

# Filled once at cleanup time: every PID in every process tree.
ALL_PIDS=()

collect_all_pids() {
  ALL_PIDS=()
  local root
  for root in "${SERVICE_ROOT_PIDS[@]:-}" "${TAIL_ROOT_PIDS[@]:-}"; do
    [[ -z "$root" ]] && continue
    while IFS= read -r pid; do
      ALL_PIDS+=("$pid")
    done < <(collect_tree_pids "$root")
  done
}

signal_all() {
  local signal="$1"
  local pid
  for pid in "${ALL_PIDS[@]:-}"; do
    kill "-$signal" "$pid" 2>/dev/null || true
  done
}

any_alive() {
  local pid
  for pid in "${ALL_PIDS[@]:-}"; do
    if kill -0 "$pid" 2>/dev/null; then
      return 0
    fi
  done
  return 1
}

force_cleanup() {
  echo " Force killing..."
  signal_all KILL
  wait 2>/dev/null || true
  rm -rf "$LOG_DIR"
  exit 1
}

cleanup() {
  # Second Ctrl+C = immediate force kill
  trap force_cleanup INT
  trap '' TERM
  trap - EXIT

  echo ""
  echo "Shutting down... (Ctrl+C again to force)"

  # 1) Walk every process tree BEFORE killing anything
  collect_all_pids

  # 2) SIGTERM everything
  signal_all TERM

  # 3) Wait up to 3 seconds for graceful shutdown
  local i
  for ((i = 0; i < 15; i++)); do
    any_alive || break
    sleep 0.2
  done

  # 4) SIGKILL anything that survived
  if any_alive; then
    signal_all KILL
  fi

  wait 2>/dev/null || true
  rm -rf "$LOG_DIR"
}

trap cleanup EXIT INT TERM

start_service() {
  local name="$1"
  shift

  local log_file="$LOG_DIR/${name}.log"
  : > "$log_file"

  # Start the service in its own session
  start_in_new_session "$@" >"$log_file" 2>&1 &
  SERVICE_ROOT_PIDS+=("$!")

  # Start log tailer in its own session
  start_in_new_session bash -c '
    tail -n 0 -F "$1" 2>/dev/null | while IFS= read -r line; do
      printf "[%s] %s\n" "$2" "$line"
    done
  ' bash "$log_file" "$name" &
  TAIL_ROOT_PIDS+=("$!")
}

start_service api "$ROOT_DIR/scripts/local/api.sh"
start_service channel-gateway "$ROOT_DIR/scripts/local/channel-gateway.sh"
start_service worker "$ROOT_DIR/scripts/local/worker.sh"
start_service knowledge-service "$ROOT_DIR/scripts/local/knowledge-service.sh"
start_service agent-runtime "$ROOT_DIR/scripts/local/agent-runtime.sh"
start_service web "$ROOT_DIR/scripts/local/web.sh"

# Wait until any service exits, then trigger cleanup
while true; do
  for pid in "${SERVICE_ROOT_PIDS[@]}"; do
    if ! kill -0 "$pid" 2>/dev/null; then
      exit 0
    fi
  done
  sleep 1
done
