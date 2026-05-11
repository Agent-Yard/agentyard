# Lynxus Extension SDK for Python

Python SDK helpers for Extension Plane implementations.

`packages/extension-protocol` is the source of truth for the extension boundary protocol. The SDK currently provides hand-written protocol constants, header builders, `ExtensionError` parsing, canonical JSON / descriptor digest helpers, static registration loading, manifest validation, and contract-testing helpers. Future generated DTOs, clients, server stubs, and validator glue must be written under package build directories such as `build/generated/*` and must not be committed to SDK source directories.
