#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_ROOT_DIR="${TMPDIR:-/tmp}/lynxus-dev"
mkdir -p "$LOG_ROOT_DIR"
LOG_DIR="$(mktemp -d "${LOG_ROOT_DIR}.XXXXXX")"

CMD_PIDS=()
TAIL_PIDS=()
CLEANED_UP=0

list_descendants() {
  local pid="$1"

  if [[ -z "$pid" ]]; then
    return
  fi

  local child_pid
  while IFS= read -r child_pid; do
    [[ -n "$child_pid" ]] || continue
    printf '%s\n' "$child_pid"
    list_descendants "$child_pid"
  done < <(pgrep -P "$pid" 2>/dev/null || true)
}

list_process_family() {
  local pid="$1"

  if [[ -z "$pid" ]]; then
    return
  fi

  printf '%s\n' "$pid"
  list_descendants "$pid"
}

list_process_groups() {
  local pid="$1"

  if [[ -z "$pid" ]]; then
    return
  fi

  local family_pid
  while IFS= read -r family_pid; do
    [[ -n "$family_pid" ]] || continue
    ps -o pgid= -p "$family_pid" 2>/dev/null | tr -d ' '
  done < <(list_process_family "$pid")
}

signal_process_family() {
  local pid="$1"
  local signal="${2:-TERM}"
  local target

  while IFS= read -r target; do
    [[ -n "$target" ]] || continue
    kill "-$signal" "$target" >/dev/null 2>&1 || true
  done < <(list_process_family "$pid" | sort -u)

  while IFS= read -r target; do
    [[ -n "$target" ]] || continue
    kill "-$signal" "--" "-$target" >/dev/null 2>&1 || true
  done < <(list_process_groups "$pid" | sort -u)
}

stop_pid() {
  local pid="$1"
  local family_pid
  local family=()

  if [[ -z "$pid" ]]; then
    return
  fi

  while IFS= read -r family_pid; do
    [[ -n "$family_pid" ]] || continue
    family+=("$family_pid")
  done < <(list_process_family "$pid" | sort -u)

  if [[ "${#family[@]}" -eq 0 ]]; then
    return
  fi

  signal_process_family "$pid" TERM

  local _attempt
  for _attempt in {1..20}; do
    local any_alive=0
    for family_pid in "${family[@]}"; do
      if kill -0 "$family_pid" >/dev/null 2>&1; then
        any_alive=1
        break
      fi
    done
    if [[ "$any_alive" -eq 0 ]]; then
      return
    fi
    sleep 0.2
  done

  signal_process_family "$pid" KILL
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

  for pid in "${CMD_PIDS[@]:-}"; do
    stop_pid "$pid"
  done

  gradle --stop >/dev/null 2>&1 || true

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
