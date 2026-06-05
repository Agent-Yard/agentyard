# @agentyard/extension-protocol

`packages/extension-protocol` is the source of truth for the Extension Plane boundary protocol.

This package owns only the cross-boundary contract between AgentYard core services and extension services:

- OpenAPI for extension HTTP operations, headers, request envelopes, responses, and `ExtensionError`.
- JSON Schema for service manifests, channel provider descriptors, tool connector descriptors, UI fields, job/schedule config, assistant binding, and shared manifest-time validation inputs.
- Examples and shared contract fixtures used by protocol self-checks and later JVM/Python SDK contract tests.
- AgentYard canonical JSON fixtures for descriptor definition and registration digest implementations.

It does not own Web-facing control-plane APIs or core-to-core internal admin APIs. Those remain in `packages/contracts/openapi/*`.

Generated SDK DTOs, clients, server stubs, validator glue, and generated helper code must not be committed to this package or to SDK source directories. JVM and Python SDK generation must write to build/generated-style directories and stay constrained by these protocol assets and contract fixtures.

Run the local protocol check with:

```sh
pnpm --filter @agentyard/extension-protocol self-check
```
