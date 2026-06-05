# Test Web Deployment

Build the web assets in the release pipeline:

```bash
VITE_API_BASE_URL=/api VITE_DEPLOY_ENV=test pnpm --filter @agentyard/web build
```

Publish `apps/web/dist/` to the web host, for example:

```text
/opt/agentyard/web/
```

Use [nginx.conf.example](nginx.conf.example) as the Nginx reference. The web host does not need Docker Compose for the console frontend. This package deploys `apps/web/dist/`, not `apps/site`.
