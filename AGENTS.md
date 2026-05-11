## Rules for this project
- DO NOT CONSIDER COMPATIBILITY WHEN CODING, INCLUDING EXISTED DATA IN DATABASE
- DO NOT FOLLOW A "PARTIAL COMPATIBILITY FIRST, GRADUAL REPLACEMENT LATER" APPROACH; PRIORITIZE DIRECT REFACTORING TOWARD THE TARGET ARCHITECTURE.
- ANY MODIFICATIONS MUST BE CONSIDERED FROM A GLOBAL PERSPECTIVE, TAKING INTO ACCOUNT THE ENTIRE PROJECT, ALL MODULES, AND THE ASSOCIATED IMPACTS ON DOCUMENTATION.
- WHEN THE SAME OR HIGHLY SIMILAR LOGIC APPEARS 3 OR MORE TIMES, EXTRACT IT PROMPTLY INTO A SHARED FUNCTION, MODULE, OR MECHANISM INSTEAD OF KEEPING SIMILAR REUSED CODE IN MULTIPLE PLACES.
- DO NOT MODIFY FILES UNDER `docs/develop_record/` unless specifically asked; they are historical records only.

## Monorepo Current Layout

```text
.github/
  workflows/   CI workflows
apps/
  api/               Spring Boot control plane API
  channel-gateway/   Spring Boot channel gateway and channel provider runtime boundary
  worker/            Temporal workflow worker
  web/               Vue + Ant Design Vue console
  site/              Vite static project site / landing page
  agent-runtime/     Python execution runtime
  knowledge-service/ Python knowledge service
demo/                Just for user demo data, no need to read this unless required
packages/
  contracts/             OpenAPI spec and shared TypeScript contracts
  contracts-jvm/         Shared JVM workflow/runtime contracts
  extension-protocol/    Extension protocol schemas, OpenAPI, examples, and contract fixtures
  extension-sdk-jvm/     JVM SDK for extension protocol helpers
  extension-sdk-python/  Python SDK and generated protocol model tooling
  persistence-jvm/       Shared JVM PostgreSQL persistence layer
  python-common/         Shared Python utilities
  shared-redis-jvm/      Shared JVM Redis keyspace / lock / pubsub layer
deploy/
  common/      Shared secret-free runtime materials for environment compose files
  local/       Docker Compose for local development dependencies
  dev/         Docker Compose for persistent dev environment
  test/        Modular test deployment templates, including compose and web/Nginx examples
scripts/
  common/      Shared env loading and process helpers
  dev/         Source-run wrappers for dev environment
  local/       Source-run wrappers for local development
docs/
  architecture/      current architecture and startup notes
  briefing/          project briefing files, no need to read this unless required
  doing/             execution ledger for active tasks only; record during execution, then clear after self-check on completion
  todo/              current detailed todo docs
  develop_record/    archived working notes and completed refactor records, no need to read this unless required
  project_structure.md current project structure notes
  technical_route.md  technical route notes
  project_todos.md   general todo document of this project
gradle/
  wrapper/     Gradle wrapper files
```

## Test Commands
- java starts with `./gradlew`
- node starts with `pnpm`
- python starts with `uv run`, do not need `PYTHONPATH`
