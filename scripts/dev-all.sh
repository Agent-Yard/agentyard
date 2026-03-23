#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT_DIR="${TMPDIR:-/tmp}/lynxus-dev"
mkdir -p "$LOG_ROOT_DIR"
LOG_DIR="$(mktemp -d "${LOG_ROOT_DIR}.XXXXXX")"

CMD_PIDS=()
TAIL_PIDS=()
CLEANED_UP=0

kill_tree() {
  local pid="$1"

  if [[ -z "$pid" ]] || ! kill -0 "$pid" >/dev/null 2>&1; then
    return
  fi

  local child_pid
  while IFS= read -r child_pid; do
    kill_tree "$child_pid"
  done < <(pgrep -P "$pid" 2>/dev/null || true)

  kill "$pid" >/dev/null 2>&1 || true
}

cleanup() {
  if [[ "$CLEANED_UP" -eq 1 ]]; then
    return
  fi
  CLEANED_UP=1

  trap - EXIT INT TERM

  for pid in "${TAIL_PIDS[@]:-}"; do
    kill_tree "$pid"
  done

  for pid in "${CMD_PIDS[@]:-}"; do
    kill_tree "$pid"
  done

  for pid in "${TAIL_PIDS[@]:-}"; do
    wait "$pid" >/dev/null 2>&1 || true
  done

  for pid in "${CMD_PIDS[@]:-}"; do
    wait "$pid" >/dev/null 2>&1 || true
  done

  rm -rf "$LOG_DIR"
}

trap cleanup EXIT INT TERM

start_process() {
  local name="$1"
  shift

  local log_file="$LOG_DIR/${name}.log"
  : > "$log_file"

  (
    cd "$ROOT_DIR"
    exec "$@" >"$log_file" 2>&1
  ) &
  local cmd_pid=$!
  CMD_PIDS+=("$cmd_pid")

  (
    tail -n 0 -F "$log_file" 2>/dev/null | while IFS= read -r line; do
      printf '[%s] %s\n' "$name" "$line"
    done
  ) &
  TAIL_PIDS+=("$!")
}

start_process api "$ROOT_DIR/scripts/dev-api.sh"
start_process worker "$ROOT_DIR/scripts/dev-worker.sh"
start_process agent-runtime "$ROOT_DIR/scripts/dev-agent-runtime.sh"
start_process web "$ROOT_DIR/scripts/dev-web.sh"

while true; do
  for pid in "${CMD_PIDS[@]}"; do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      exit 0
    fi
  done
  sleep 1
done
