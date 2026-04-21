## Rules for this project
- DO NOT CONSIDER COMPATIBILITY WHEN CODING, INCLUDING EXISTED DATA IN DATABASE
- DO NOT FOLLOW A "PARTIAL COMPATIBILITY FIRST, GRADUAL REPLACEMENT LATER" APPROACH; PRIORITIZE DIRECT REFACTORING TOWARD THE TARGET ARCHITECTURE.
- ANY MODIFICATIONS MUST BE CONSIDERED FROM A GLOBAL PERSPECTIVE, TAKING INTO ACCOUNT THE ENTIRE PROJECT, ALL MODULES, AND THE ASSOCIATED IMPACTS ON DOCUMENTATION.
- WHEN THE SAME OR HIGHLY SIMILAR LOGIC APPEARS 3 OR MORE TIMES, EXTRACT IT PROMPTLY INTO A SHARED FUNCTION, MODULE, OR MECHANISM INSTEAD OF KEEPING SIMILAR REUSED CODE IN MULTIPLE PLACES.
- DO NOT MODIFY FILES UNDER `docs/develop_record/` unless specifically asked; they are historical records only.

## Monorepo Current Layout

```text
apps/
  api/         Spring Boot control plane API
  worker/      Temporal workflow worker
  web/         Vue + Ant Design Vue console
  agent-runtime/ Python execution runtime
  knowledge-service Knowledge base service
demo/         Just for user demo data, no need to read this unless required
packages/
  contracts/   OpenAPI spec and shared TypeScript contracts
  contracts-jvm/ Shared JVM workflow/runtime contracts
infra/
  dev/         Docker Compose for dev environment
  local/       Docker Compose for local development
scripts/       Local startup wrappers and env loading
docs/
  architecture/      current architecture and startup notes
  briefing/          project briefing files, no need to read this unless required
  doing/             active execution docs and in-progress task context
  project_todos.md   general todo document of this project
  todo/              current detailed todo docs
  develop_record/    archived working notes and completed refactor records, no need to read this unless required
```

## Test Commands
- java uses ./gradlew
- node uses command in local system
- python uses `uv run` from project root workspace
