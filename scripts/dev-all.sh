#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT_DIR="${TMPDIR:-/tmp}/lynxus-dev"
mkdir -p "$LOG_ROOT_DIR"
LOG_DIR="$(mktemp -d "${LOG_ROOT_DIR}.XXXXXX")"

CMD_PIDS=()
CMD_GROUPS=()
TAIL_PIDS=()
CLEANED_UP=0

kill_tree() {
  local pid="$1"
  local signal="${2:-TERM}"

  if [[ -z "$pid" ]] || ! kill -0 "$pid" >/dev/null 2>&1; then
    return
  fi

  local child_pid
  while IFS= read -r child_pid; do
    kill_tree "$child_pid" "$signal"
  done < <(pgrep -P "$pid" 2>/dev/null || true)

  kill "-$signal" "$pid" >/dev/null 2>&1 || true
}

kill_group() {
  local pgid="$1"
  local signal="${2:-TERM}"

  if [[ -z "$pgid" ]]; then
    return
  fi

  kill "-$signal" "--" "-$pgid" >/dev/null 2>&1 || true
}

stop_pid() {
  local pid="$1"

  if [[ -z "$pid" ]] || ! kill -0 "$pid" >/dev/null 2>&1; then
    return
  fi

  kill_tree "$pid" TERM

  local _attempt
  for _attempt in {1..20}; do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      return
    fi
    sleep 0.2
  done

  kill_tree "$pid" KILL
}

stop_group() {
  local pgid="$1"

  if [[ -z "$pgid" ]]; then
    return
  fi

  kill_group "$pgid" TERM

  local _attempt
  for _attempt in {1..20}; do
    if ! pgrep -g "$pgid" >/dev/null 2>&1; then
      return
    fi
    sleep 0.2
  done

  kill_group "$pgid" KILL
}

cleanup() {
  if [[ "$CLEANED_UP" -eq 1 ]]; then
    return
  fi
  CLEANED_UP=1

  trap - EXIT INT TERM

  for pid in "${TAIL_PIDS[@]:-}"; do
    stop_pid "$pid"
  done

  for pgid in "${CMD_GROUPS[@]:-}"; do
    stop_group "$pgid"
  done

  for pid in "${CMD_PIDS[@]:-}"; do
    stop_pid "$pid"
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

  if command -v setsid >/dev/null 2>&1; then
    (
      cd "$ROOT_DIR"
      exec setsid "$@" >"$log_file" 2>&1
    ) &
  else
    (
      cd "$ROOT_DIR"
      exec "$@" >"$log_file" 2>&1
    ) &
  fi
  local cmd_pid=$!
  CMD_PIDS+=("$cmd_pid")
  CMD_GROUPS+=("$cmd_pid")

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
