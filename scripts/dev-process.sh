#!/usr/bin/env bash

# Start a command in a new session (new SID + PGID).
start_in_new_session() {
  python3 -c 'import os, sys; os.setsid(); os.execvp(sys.argv[1], sys.argv[1:])' "$@"
}

# Collect all PIDs in a process tree (root + all descendants).
# Must be called BEFORE killing the root, otherwise children get
# reparented to PID 1 and become unfindable.
collect_tree_pids() {
  local pid=$1
  # Skip if process is already dead
  kill -0 "$pid" 2>/dev/null || return 0
  echo "$pid"
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    collect_tree_pids "$child"
  done
}
