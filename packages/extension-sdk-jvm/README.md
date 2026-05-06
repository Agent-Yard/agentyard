# Lynxus Extension SDK for JVM

JVM SDK for Extension Plane implementations.

`packages/extension-protocol` is the source of truth for the extension boundary protocol.

The SDK generates Jackson-friendly protocol DTO sources under `build/generated/*` during the Gradle build and compiles them into the published SDK artifact. Generated sources are build output only and must not be committed to SDK source directories or copied into extension projects.

The SDK artifact also packages the shared protocol JSON Schema files as classpath resources. `ManifestValidator.validate(...)` and `validateJson(...)` use those bundled schemas by default, so consumers of the published SDK do not need to deploy `packages/extension-protocol/json-schema` beside their application.

Descriptor-provided JSON Schema checks should go through `JsonSchemaValues` instead of depending on the underlying schema-validator library directly.
