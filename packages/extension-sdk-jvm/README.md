# Lynxus Extension SDK for JVM

Skeleton JVM SDK for Extension Plane implementations.

`packages/extension-protocol` is the source of truth for the extension boundary protocol. Future generated DTOs, clients, server stubs, and validator glue must be written under Gradle build directories such as `build/generated/*` and must not be committed to SDK source directories.
