#!/usr/bin/env bash

set -euo pipefail

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
POSTGRES_USER="${POSTGRES_USER:-agentyard}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-agentyard}"
AGENTYARD_APP_DATABASE_USER="${AGENTYARD_APP_DATABASE_USER:-$POSTGRES_USER}"
AGENTYARD_APP_DATABASE_PASSWORD="${AGENTYARD_APP_DATABASE_PASSWORD:-}"
AGENTYARD_TEMPORAL_DATABASE_USER="${AGENTYARD_TEMPORAL_DATABASE_USER:-}"
AGENTYARD_TEMPORAL_DATABASE_PASSWORD="${AGENTYARD_TEMPORAL_DATABASE_PASSWORD:-}"
AGENTYARD_CORE_DATABASE="${AGENTYARD_CORE_DATABASE:-agentyard_core}"
AGENTYARD_CHANNEL_GATEWAY_DATABASE="${AGENTYARD_CHANNEL_GATEWAY_DATABASE:-agentyard_channel_gateway}"
AGENTYARD_KNOWLEDGE_DATABASE="${AGENTYARD_KNOWLEDGE_DATABASE:-agentyard_knowledge}"
AGENTYARD_AGENT_RUNTIME_DATABASE="${AGENTYARD_AGENT_RUNTIME_DATABASE:-agentyard_agent_runtime}"
AGENTYARD_TEMPORAL_DATABASE="${AGENTYARD_TEMPORAL_DATABASE:-}"
AGENTYARD_TEMPORAL_VISIBILITY_DATABASE="${AGENTYARD_TEMPORAL_VISIBILITY_DATABASE:-}"

export PGPASSWORD="$POSTGRES_PASSWORD"

wait_for_postgres() {
  until psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres -c "select 1" >/dev/null 2>&1; do
    sleep 1
  done
}

sql_literal() {
  printf "%s" "$1" | sed "s/'/''/g"
}

sql_identifier() {
  printf "%s" "$1" | sed 's/"/""/g'
}

create_database_if_missing() {
  local database_name="$1"
  local owner_name="${2:-$POSTGRES_USER}"
  local exists
  local database_literal

  database_literal="$(sql_literal "$database_name")"
  exists="$(
    psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres -tAc \
      "select 1 from pg_database where datname = '$database_literal'"
  )"

  if [[ "$exists" != "1" ]]; then
    createdb -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -O "$owner_name" "$database_name"
  fi
}

create_role_if_missing() {
  local role_name="$1"
  local role_password="$2"
  local role_literal
  local role_identifier
  local password_literal
  local exists

  role_literal="$(sql_literal "$role_name")"
  role_identifier="$(sql_identifier "$role_name")"
  password_literal="$(sql_literal "$role_password")"
  exists="$(
    psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres -tAc \
      "select 1 from pg_roles where rolname = '$role_literal'"
  )"

  if [[ "$exists" == "1" ]]; then
    psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres \
      -c "alter role \"$role_identifier\" login password '$password_literal'" >/dev/null
  else
    psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d postgres \
      -c "create role \"$role_identifier\" login password '$password_literal'" >/dev/null
  fi
}

enable_extension_if_missing() {
  local database_name="$1"
  local extension_name="$2"
  psql -h "$POSTGRES_HOST" -U "$POSTGRES_USER" -d "$database_name" -c "create extension if not exists $extension_name" >/dev/null
}

wait_for_postgres

if [[ "$AGENTYARD_APP_DATABASE_USER" != "$POSTGRES_USER" ]]; then
  create_role_if_missing "$AGENTYARD_APP_DATABASE_USER" "$AGENTYARD_APP_DATABASE_PASSWORD"
fi

if [[ -n "$AGENTYARD_TEMPORAL_DATABASE_USER" && "$AGENTYARD_TEMPORAL_DATABASE_USER" != "$POSTGRES_USER" ]]; then
  create_role_if_missing "$AGENTYARD_TEMPORAL_DATABASE_USER" "$AGENTYARD_TEMPORAL_DATABASE_PASSWORD"
fi

create_database_if_missing "$AGENTYARD_CORE_DATABASE" "$AGENTYARD_APP_DATABASE_USER"
create_database_if_missing "$AGENTYARD_CHANNEL_GATEWAY_DATABASE" "$AGENTYARD_APP_DATABASE_USER"
create_database_if_missing "$AGENTYARD_KNOWLEDGE_DATABASE" "$AGENTYARD_APP_DATABASE_USER"
create_database_if_missing "$AGENTYARD_AGENT_RUNTIME_DATABASE" "$AGENTYARD_APP_DATABASE_USER"
enable_extension_if_missing "$AGENTYARD_KNOWLEDGE_DATABASE" "vector"
enable_extension_if_missing "$AGENTYARD_KNOWLEDGE_DATABASE" "pg_trgm"

if [[ -n "$AGENTYARD_TEMPORAL_DATABASE" ]]; then
  create_database_if_missing "$AGENTYARD_TEMPORAL_DATABASE" "${AGENTYARD_TEMPORAL_DATABASE_USER:-$POSTGRES_USER}"
fi

if [[ -n "$AGENTYARD_TEMPORAL_VISIBILITY_DATABASE" ]]; then
  create_database_if_missing "$AGENTYARD_TEMPORAL_VISIBILITY_DATABASE" "${AGENTYARD_TEMPORAL_DATABASE_USER:-$POSTGRES_USER}"
fi
