#!/usr/bin/env bash

set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_USER="${POSTGRES_USER:-lynxus}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-lynxus}"
LYNXUS_CORE_DATABASE="${LYNXUS_CORE_DATABASE:-lynxus_core}"
LYNXUS_KNOWLEDGE_DATABASE="${LYNXUS_KNOWLEDGE_DATABASE:-lynxus_knowledge}"

export PGPASSWORD="$POSTGRES_PASSWORD"

wait_for_postgres() {
  until psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres -c "select 1" >/dev/null 2>&1; do
    sleep 1
  done
}

create_database_if_missing() {
  local database_name="$1"
  local exists

  exists="$(
    psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres -tAc \
      "select 1 from pg_database where datname = '$database_name'"
  )"

  if [[ "$exists" != "1" ]]; then
    createdb -h "$POSTGRES_HOST" -U "$POSTGRES_USER" "$database_name"
  fi
}

enable_extension_if_missing() {
  local database_name="$1"
  local extension_name="$2"
  psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d "$database_name" -c "create extension if not exists $extension_name" >/dev/null
}

wait_for_postgres
create_database_if_missing "$LYNXUS_CORE_DATABASE"
create_database_if_missing "$LYNXUS_KNOWLEDGE_DATABASE"
enable_extension_if_missing "$LYNXUS_KNOWLEDGE_DATABASE" "vector"
enable_extension_if_missing "$LYNXUS_KNOWLEDGE_DATABASE" "pg_trgm"
