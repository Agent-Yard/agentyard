# Lynxus Extension Template

Standalone Java 21 + Spring Boot starter for an external Lynxus extension service.

This project is intentionally independent from the Lynxus monorepo. Copy the whole `samples/extension-template` directory into a new repository, then replace the descriptor schemas and handler implementations with your provider/tool logic.

## What Is Included

- `GET /extension/manifest` returns a generated manifest: descriptor/schema content comes from `ExtensionDescriptorRegistry`, while HTTP endpoint paths come from `ExtensionEndpointPaths`.
- `GET /extension/health`, `GET /health/live`, and `GET /health/ready` provide protocol and container health probes.
- `POST /tools/invoke` is the Tool Connector runtime entrypoint.
- `POST /channel/run-job` is the Channel Provider job entrypoint.
- `POST /credentials`, `/credentials/rotate`, `/credentials/revoke`, and `/credentials/validate` are remote credential lifecycle placeholders.
- `LynxusChannelGatewayClient` contains placeholders for channel inbound event submission, outbound subscription bootstrap, stream URI creation, and final-delivery ACK.
- `InternalTokenAuthenticationFilter` validates `Authorization: Bearer <token>` using `LYNXUS_INTERNAL_AUTH_TOKEN`.
- Controllers and handlers use SDK generated protocol DTOs from `com.lynxus.extension.sdk.generated.protocol.model`.
- `ExtensionManifestFactory` validates the generated manifest with `com.lynxus:extension-sdk-jvm`.

## Build Assumptions

`build.gradle.kts` depends on:

```kotlin
implementation("com.lynxus:extension-sdk-jvm:0.1.0")
```

The template assumes that artifact has been published to a Maven repository. If it is hosted in a private repository, add that repository in `settings.gradle.kts`.

## Local Run

Install JDK 21 or newer; the Gradle build compiles with `--release 21`.

```bash
export LYNXUS_INTERNAL_AUTH_TOKEN=replace-with-shared-secret
./gradlew bootRun
```

## Register With Lynxus

Give the deployment operator a registration entry equivalent to `registration.example.yml`.

The manifest descriptor IDs and registration `exposes` lists must stay aligned:

- channel provider: `example.extension.template.channel`
- tool connector: `example.extension.template.tool`

## Replace Before Production

1. Edit `ExtensionDescriptorRegistry`.
2. Replace `TemplateToolConnectorHandler` with real Tool operation dispatch.
3. Replace `TemplateChannelProviderHandler` with pull-job behavior, or remove `jobDefinitions` for webhook-only providers.
4. Implement outbound frame consumption by opening the SSE URI from `LynxusChannelGatewayClient.outboundFrameStreamUri`.
5. Implement credential lifecycle with your own vault; never return or log cleartext credentials. Remove `credentialLifecycleEndpointProfile` from descriptors that do not support remote credential lifecycle; remove `CREDENTIAL_VALIDATE` from the default profile if standalone validation is not supported. `validateCredential` is optional, and the template keeps it in the default profile only as a ready-to-fill placeholder.
6. Keep `Authorization`, descriptor headers, trace headers, and idempotency handling intact.
