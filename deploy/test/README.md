# Test Deployment Package

This directory is the single source for test deployment configuration. It is published together with `deploy/common` when a target module needs shared runtime material.

The test environment is deployed by module. Do not assume all services run on one host.

## Layout

```text
deploy/test/
  .env.example
  compose/
    api.yml
    worker.yml
    channel-gateway.yml
    knowledge-service.yml
    agent-runtime.yml
    sandbox.yml
    postgres.yml
    temporal.yml
    temporal-ui.yml
  web/
    nginx.conf.example
    README.md
```

## Host Setup

Recommended target layout:

```text
/opt/lynxus/
  common/
  test/
    compose/
    .env
```

Each host may keep only the compose files and env variables needed by that host. The PostgreSQL module requires this relative layout because it mounts `../../common/postgres-bootstrap`.

Start the module:

```bash
docker compose --env-file test/.env -f test/compose/api.yml up -d
```

Upgrade the module after changing the image tag in `.env`:

```bash
docker compose --env-file test/.env -f test/compose/api.yml pull
docker compose --env-file test/.env -f test/compose/api.yml up -d
```

## Web

The web frontend is not deployed with Docker Compose. Build static assets in the release pipeline and publish them to the existing Nginx host. See [web/README.md](/Users/eric/projects/lynxus/deploy/test/web/README.md).

## Infrastructure Dependencies

The test deployment uses existing Redis, S3-compatible object storage, and Nginx.

PostgreSQL is listed as its own module because test may either use a managed/existing PostgreSQL service or deploy a dedicated PostgreSQL host. Deploy it with [compose/postgres.yml](/Users/eric/projects/lynxus/deploy/test/compose/postgres.yml) when the test environment does not already provide PostgreSQL.

The PostgreSQL module uses [../common/postgres-bootstrap/init-databases.sh](/Users/eric/projects/lynxus/deploy/common/postgres-bootstrap/init-databases.sh) through the published `common + test` layout. Its one-shot bootstrap service creates:

- `lynxus_core`
- `lynxus_knowledge`
- `temporal`
- `temporal_visibility`

It also enables `vector` and `pg_trgm` in the knowledge database.

Temporal is included as its own module because test has no existing Temporal service. Deploy it on its own host or a dedicated runtime host with [compose/temporal.yml](/Users/eric/projects/lynxus/deploy/test/compose/temporal.yml).

Sandbox is also a separate module. Deploy it with [compose/sandbox.yml](/Users/eric/projects/lynxus/deploy/test/compose/sandbox.yml).

## PostgreSQL Requirement

Use PostgreSQL 16+; PostgreSQL 17 is recommended. The knowledge database must have `pgvector` with HNSW support and `pg_trgm` available.
