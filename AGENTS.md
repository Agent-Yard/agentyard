## Rules for this project
- DO NOT CONSIDER COMPATIBILITY WHEN CODING (this project is under development and not released yet).
- ANY MODIFICATIONS MUST BE CONSIDERED FROM A GLOBAL PERSPECTIVE, TAKING INTO ACCOUNT THE ENTIRE PROJECT, ALL MODULES, AND THE ASSOCIATED IMPACTS ON DOCUMENTATION.

## Monorepo Layout

```text
apps/
  api/         Spring Boot control plane API
  worker/      Temporal workflow worker
  web/         Vue + Ant Design Vue console
  agent-runtime/ Python execution runtime
  knowledge-service Knowledge base service
packages/
  contracts/   OpenAPI spec and shared TypeScript contracts
  contracts-jvm/ Shared JVM workflow/runtime contracts
infra/
  local/       Docker Compose for local development
scripts/       Local startup wrappers and env loading
docs/
  architecture/ current architecture and startup notes
  briefing/     project briefing files, no need to read this unless required
  todo/         current backlog and next-step docs
  develop_record/ working notes and refactor records, no need to read this unless required
```

## test commands
- java and node use commands in local system
- python uses .venv/bin/python in project root