# Lynxus Extension SDK for JVM

JVM SDK for Extension Plane implementations.

`packages/extension-protocol` is the source of truth for the extension boundary protocol.

The SDK generates Jackson-friendly protocol DTO sources under `build/generated/*` during the Gradle build and compiles them into the published SDK artifact. Generated sources are build output only and must not be committed to SDK source directories or copied into extension projects.
