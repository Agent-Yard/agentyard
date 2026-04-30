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
- `lynxus_channel_gateway`
- `lynxus_knowledge`
- `temporal`
- `temporal_visibility`

It also enables `vector` and `pg_trgm` in the knowledge database.

Temporal is included as its own module because test has no existing Temporal service. Deploy it on its own host or a dedicated runtime host with [compose/temporal.yml](/Users/eric/projects/lynxus/deploy/test/compose/temporal.yml).

Sandbox is also a separate module. Deploy it with [compose/sandbox.yml](/Users/eric/projects/lynxus/deploy/test/compose/sandbox.yml).

## Startup Order

The compose files are intentionally independent because modules may run on different hosts. Cross-module order is not expressed with `depends_on`; the deployment pipeline or runbook must enforce it.

Recommended order when PostgreSQL is deployed by this package:

1. PostgreSQL:

   ```bash
   docker compose --env-file test/.env -f test/compose/postgres.yml up -d
   ```

   Wait for `postgres-bootstrap` to exit successfully before starting Temporal or application services.

2. Temporal:

   ```bash
   docker compose --env-file test/.env -f test/compose/temporal.yml up -d
   ```

3. Sandbox:

   ```bash
   docker compose --env-file test/.env -f test/compose/sandbox.yml up -d
   ```

4. Internal services:

   ```bash
   docker compose --env-file test/.env -f test/compose/channel-gateway.yml up -d
   docker compose --env-file test/.env -f test/compose/knowledge-service.yml up -d
   docker compose --env-file test/.env -f test/compose/agent-runtime.yml up -d
   ```

5. Worker:

   ```bash
   docker compose --env-file test/.env -f test/compose/worker.yml up -d
   ```

6. API:

   ```bash
   docker compose --env-file test/.env -f test/compose/api.yml up -d
   ```

7. Web static assets and Nginx.

When PostgreSQL is managed outside this package, the deployer must create `lynxus_core`, `lynxus_channel_gateway`, `lynxus_knowledge`, `temporal`, and `temporal_visibility` before starting Temporal or application services. The knowledge database must have `vector` and `pg_trgm` enabled.

## Dependency Matrix

| Module | Requires |
| --- | --- |
| postgres | Persistent disk |
| temporal | PostgreSQL `temporal` and `temporal_visibility` databases |
| sandbox | No Lynxus service dependency |
| channel-gateway | PostgreSQL `lynxus_channel_gateway` |
| knowledge-service | PostgreSQL `lynxus_knowledge`, S3-compatible object storage, embedding provider |
| agent-runtime | Redis, API URL, Knowledge Service URL, model provider credentials as needed |
| worker | PostgreSQL `lynxus_core`, Redis, Temporal, Agent Runtime, Knowledge Service, Sandbox |
| api | PostgreSQL `lynxus_core`, Redis, Temporal, Knowledge Service, Channel Gateway, OIDC |
| web | API through Nginx `/api`, `/oauth2`, and `/login/oauth2` routes |

## PostgreSQL Requirement

Use PostgreSQL 16+; PostgreSQL 17 is recommended. The knowledge database must have `pgvector` with HNSW support and `pg_trgm` available.
Runtime datasource URLs are service-scoped: `api` and `worker` use `LYNXUS_CORE_DATASOURCE_URL`, while `channel-gateway` uses `LYNXUS_CHANNEL_GATEWAY_DATASOURCE_URL`. Do not set a shared `SPRING_DATASOURCE_URL` in deployment env files because it overrides every Spring Boot service datasource.
