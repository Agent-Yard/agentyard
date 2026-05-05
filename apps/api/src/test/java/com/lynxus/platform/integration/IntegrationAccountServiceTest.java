package com.lynxus.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lynxus.platform.extension.ExtensionDefinitionRegistryNotReadyException;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.extension.sdk.registration.ExtensionRegistrationLoader;
import com.lynxus.platform.extension.ExtensionManifestFetcher;
import com.lynxus.platform.extension.ExtensionRegistrationProperties;
import com.lynxus.platform.extension.ExtensionRegistrationService;
import com.lynxus.platform.extension.ExtensionDefinitionService;
import com.lynxus.platform.extension.ExtensionDefinitionService.ExtensionDefinitionRegistry;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountCredentialRequest;
import com.lynxus.platform.integration.IntegrationDtos.CreateIntegrationAccountRequest;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityBlock;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityRisk;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountCredentialStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountStatus;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import com.lynxus.platform.integration.IntegrationDtos.RemoteCredentialLifecycleRequest;
import com.lynxus.platform.integration.IntegrationDtos.RemoteCredentialLifecycleResponse;
import com.lynxus.platform.integration.IntegrationDtos.RotateIntegrationAccountCredentialRequest;
import com.lynxus.platform.integration.IntegrationDtos.StoredIntegrationAccount;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountStatusRequest;
import com.lynxus.platform.integration.IntegrationDtos.UpdateIntegrationAccountRequest;
import com.lynxus.platform.shared.ApiProblemException;
import com.lynxus.platform.shared.ConflictException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IntegrationAccountServiceTest {
    @Test
    void shouldUseKeyedCredentialFingerprint() {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> credential = Map.of("businessCode", "biz-001", "secretKey", "secret-001");

        var first = new IntegrationCredentialCrypto(objectMapper, "test-encryption-key-a").encrypt(credential);
        var second = new IntegrationCredentialCrypto(objectMapper, "test-encryption-key-b").encrypt(credential);

        assertTrue(first.fingerprint().startsWith("v1:"));
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void shouldStoreCredentialEncryptedAndExposeDecryptedCredentialOnlyForRuntime() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );

        StoredIntegrationAccount existing = storedAccount(
            "integration-account-runtime",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            IntegrationAccountStatus.ENABLED,
            Map.of("baseUrl", "https://vendor.example"),
            null,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key")
                .encrypt(Map.of("businessCode", "biz-001", "secretKey", "secret-001"))
                .ciphertext(),
            "fingerprint-must-not-leak"
        );
        saved.set(existing);
        var account = service.getAccount(existing.id());
        var runtimeCredential = service.runtimeCredential(existing.id());

        assertFalse(account.hasExternalSecretRef());
        assertTrue(account.credentialConfigured());
        assertEquals(IntegrationAccountCredentialStatus.ACTIVE, account.credentialStatus());
        assertFalse(saved.get().credentialCiphertext().contains("secret-001"));
        assertEquals(IntegrationAccountSubjectType.TOOL_CONNECTOR, runtimeCredential.subjectType());
        assertEquals("simple-http", runtimeCredential.subjectId());
        assertEquals("biz-001", runtimeCredential.credential().get("businessCode"));
        assertEquals("secret-001", runtimeCredential.credential().get("secretKey"));
    }

    @Test
    void createWithCoreCredentialEncryptsValidatesAndPublicDtoRedactsSecrets() throws Exception {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Simple HTTP Account",
            null,
            Map.of(),
            Map.of("bearerToken", "secret-token")
        ));

        assertEquals(IntegrationAccountCredentialStatus.ACTIVE, created.credentialStatus());
        assertFalse(created.hasExternalSecretRef());
        assertTrue(created.credentialConfigured());
        assertNotNull(saved.get().credentialCiphertext());
        assertNotNull(saved.get().credentialFingerprint());
        assertFalse(saved.get().credentialCiphertext().contains("secret-token"));
        assertEquals(Map.of("bearerToken", "secret-token"), service.runtimeCredential(created.id()).credential());

        String publicJson = new ObjectMapper().writeValueAsString(created);
        assertFalse(publicJson.contains("externalSecretRef"));
        assertFalse(publicJson.contains("credentialCiphertext"));
        assertFalse(publicJson.contains("credentialFingerprint"));
        assertFalse(publicJson.contains("secret-token"));
        assertFalse(publicJson.contains("metadata"));
    }

    @Test
    void coreCredentialValidateAndRevokeStayLocal() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService(),
            (baseUrl, path, request, traceId, requestId) -> {
                throw new AssertionError("core encrypted reference must not call remote lifecycle");
            }
        );

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Simple HTTP Account",
            null,
            Map.of(),
            Map.of("bearerToken", "secret-token")
        ));
        var validated = service.validateCredential(created.id());
        assertEquals(IntegrationAccountCredentialStatus.ACTIVE, validated.credentialStatus());

        var revoked = service.revokeCredential(created.id());
        assertEquals(IntegrationAccountCredentialStatus.REVOKED, revoked.credentialStatus());
        assertFalse(revoked.credentialConfigured());
        assertNull(saved.get().credentialCiphertext());
        assertNull(saved.get().credentialFingerprint());
        assertEquals(Map.of(), service.runtimeCredential(created.id()).credential());
    }

    @Test
    void unsupportedDescriptorAllowsConfigOnlyButRejectsCredentialPayloads() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );

        var configOnly = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "mcp",
            "MCP Account",
            null,
            Map.of()
        ));
        assertEquals(IntegrationAccountCredentialStatus.NOT_CONFIGURED, configOnly.credentialStatus());

        ApiProblemException createPayload = assertThrows(ApiProblemException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "mcp",
                "MCP With Secret",
                null,
                Map.of(),
                Map.of("apiKey", "secret")
            )
        ));
        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_UNSUPPORTED", createPayload.code());

        ApiProblemException lifecycle = assertThrows(ApiProblemException.class, () -> service.createCredential(
            configOnly.id(),
            new CreateIntegrationAccountCredentialRequest(Map.of("apiKey", "secret"))
        ));
        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_UNSUPPORTED", lifecycle.code());
    }

    @Test
    void remoteCreatePersistsAccountBeforeCallAndIgnoresMetadata() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        AtomicReference<StoredIntegrationAccount> savedWhenRemoteCalled = new AtomicReference<>();
        AtomicReference<RemoteCredentialLifecycleRequest> request = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) -> {
                savedWhenRemoteCalled.set(saved.get());
                request.set(lifecycleRequest);
                assertEquals("https://remote.example.com/private", baseUrl);
                assertEquals("/credentials/create", path);
                return new RemoteCredentialLifecycleResponse("vault://opaque-ref", IntegrationAccountCredentialStatus.ACTIVE);
            }
        );

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            null,
            Map.of(),
            Map.of("apiKey", "secret-api-key")
        ));

        assertNotNull(savedWhenRemoteCalled.get());
        assertTrue(savedWhenRemoteCalled.get().id().startsWith("integration-account-"));
        assertEquals(IntegrationAccountCredentialStatus.ACTIVE, created.credentialStatus());
        assertTrue(created.hasExternalSecretRef());
        assertEquals("Remote Account", saved.get().name());
        assertEquals(Map.of(), saved.get().metadata());
        assertEquals(Map.of("type", "TOOL_CONNECTOR", "id", "enterprise.acme.crm"), request.get().descriptor());
        assertEquals(Map.of("config", Map.of()), request.get().account());
        assertFalse(request.get().account().containsKey("accountId"));
        assertFalse(request.get().account().containsKey("metadata"));
    }

    @Test
    void remoteCreateFailureKeepsGeneratedAccountValidationFailedWithoutSecrets() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) -> {
                throw new ApiProblemException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED",
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED: credential lifecycle call failed"
                );
            }
        );

        ApiProblemException error = assertThrows(ApiProblemException.class, () -> service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            null,
            Map.of(),
            Map.of("apiKey", "secret-api-key")
        )));

        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED", error.code());
        assertEquals(IntegrationAccountCredentialStatus.VALIDATION_FAILED, saved.get().credentialStatus());
        assertNull(saved.get().externalSecretRef());
        assertNull(saved.get().credentialCiphertext());
        assertNull(saved.get().credentialFingerprint());
    }

    @Test
    void standaloneRemoteCreateFailureMarksExistingAccountValidationFailedWithoutSecrets() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>(storedAccount(
            "integration-account-remote",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            null,
            null,
            null,
            IntegrationAccountCredentialStatus.NOT_CONFIGURED
        ));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) -> {
                throw new ApiProblemException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED",
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED: credential lifecycle call failed"
                );
            }
        );

        ApiProblemException error = assertThrows(ApiProblemException.class, () -> service.createCredential(
            saved.get().id(),
            new CreateIntegrationAccountCredentialRequest(Map.of("apiKey", "secret-api-key"))
        ));

        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED", error.code());
        assertEquals(IntegrationAccountCredentialStatus.VALIDATION_FAILED, saved.get().credentialStatus());
        assertNull(saved.get().externalSecretRef());
        assertNull(saved.get().credentialCiphertext());
        assertNull(saved.get().credentialFingerprint());
    }

    @Test
    void remoteRotateRequiresSameRefAndFailureKeepsExistingRefWithRotationRequired() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        saved.set(storedAccount(
            "integration-account-remote",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            "vault://existing-ref",
            null,
            null
        ));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));

        IntegrationAccountService mismatched = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) ->
                new RemoteCredentialLifecycleResponse("vault://different-ref", IntegrationAccountCredentialStatus.ACTIVE)
        );
        ApiProblemException mismatch = assertThrows(ApiProblemException.class, () -> mismatched.rotateCredential(
            saved.get().id(),
            new RotateIntegrationAccountCredentialRequest(Map.of("apiKey", "new-secret"))
        ));
        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED", mismatch.code());
        assertEquals("vault://existing-ref", saved.get().externalSecretRef());
        assertEquals(IntegrationAccountCredentialStatus.ROTATION_REQUIRED, saved.get().credentialStatus());

        IntegrationAccountService successful = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) ->
                new RemoteCredentialLifecycleResponse("vault://existing-ref", IntegrationAccountCredentialStatus.ACTIVE)
        );
        var rotated = successful.rotateCredential(
            saved.get().id(),
            new RotateIntegrationAccountCredentialRequest(Map.of("apiKey", "new-secret"))
        );
        assertEquals(IntegrationAccountCredentialStatus.ACTIVE, rotated.credentialStatus());
        assertTrue(rotated.hasExternalSecretRef());
    }

    @Test
    void remoteValidateStatusUpdatesButTransportFailureDoesNotPretendResult() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>(storedAccount(
            "integration-account-remote",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            "vault://existing-ref",
            null,
            null
        ));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));

        IntegrationAccountService validationFailed = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) ->
                new RemoteCredentialLifecycleResponse(null, IntegrationAccountCredentialStatus.VALIDATION_FAILED)
        );
        assertEquals(IntegrationAccountCredentialStatus.VALIDATION_FAILED, validationFailed.validateCredential(saved.get().id()).credentialStatus());

        IntegrationAccountService transportFailed = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) -> {
                throw new ApiProblemException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED",
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED: credential lifecycle call failed"
                );
            }
        );
        ApiProblemException error = assertThrows(ApiProblemException.class, () -> transportFailed.validateCredential(saved.get().id()));
        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED", error.code());
        assertEquals(IntegrationAccountCredentialStatus.VALIDATION_FAILED, saved.get().credentialStatus());
    }

    @Test
    void remoteRevokeFailureKeepsRefAndMarksRevokeFailed() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>(storedAccount(
            "integration-account-remote",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "enterprise.acme.crm",
            "Remote Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            "vault://existing-ref",
            null,
            null
        ));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) -> {
                throw new ApiProblemException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED",
                    "INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED: credential lifecycle call failed"
                );
            }
        );

        ApiProblemException error = assertThrows(ApiProblemException.class, () -> service.revokeCredential(saved.get().id()));

        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED", error.code());
        assertEquals("vault://existing-ref", saved.get().externalSecretRef());
        assertEquals(IntegrationAccountCredentialStatus.REVOKE_FAILED, saved.get().credentialStatus());
    }

    @Test
    void credentialLifecycleLockReturnsRepositoryConflictBeforeLifecycleStarts() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        org.mockito.Mockito.doThrow(new ConflictException("integration account credential lifecycle operation is already running"))
            .when(repository)
            .acquireCredentialLifecycleLock("integration-account-remote");
        AtomicReference<Boolean> remoteInvoked = new AtomicReference<>(false);
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            remoteDefinitionService(),
            (baseUrl, path, lifecycleRequest, traceId, requestId) -> {
                remoteInvoked.set(true);
                return new RemoteCredentialLifecycleResponse(null, IntegrationAccountCredentialStatus.ACTIVE);
            }
        );

        ConflictException error = assertThrows(
            ConflictException.class,
            () -> service.validateCredential(" integration-account-remote ")
        );

        assertEquals("integration account credential lifecycle operation is already running", error.getMessage());
        assertFalse(remoteInvoked.get());
        org.mockito.Mockito.verify(repository).acquireCredentialLifecycleLock("integration-account-remote");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).findAccount(any());
    }

    @Test
    void concreteRemoteClientSendsOnlyCredentialLifecycleHeadersAndSanitizesFailures() throws Exception {
        AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> validateBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/private/credentials/create", exchange -> {
            headers.set(exchange.getRequestHeaders());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"externalSecretRef":"vault://opaque-ref","credentialStatus":"ACTIVE","metadata":{"ignored":"value"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.createContext("/private/credentials/validate", exchange -> {
            validateBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"credentialStatus":"ACTIVE"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            JdkIntegrationCredentialLifecycleClient client = new JdkIntegrationCredentialLifecycleClient(new ObjectMapper(), "internal-token");
            RemoteCredentialLifecycleResponse response = client.invoke(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/private",
                "/credentials/create",
                new RemoteCredentialLifecycleRequest(
                    Map.of("type", "TOOL_CONNECTOR", "id", "enterprise.acme.crm"),
                    Map.of("config", Map.of()),
                    Map.of("apiKey", "secret-api-key"),
                    Map.of("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                ),
                "11111111111111111111111111111111",
                "request-1"
            );

            assertEquals("vault://opaque-ref", response.externalSecretRef());
            assertEquals(List.of("Bearer internal-token"), headers.get().get("Authorization"));
            assertEquals(List.of("11111111111111111111111111111111"), headers.get().get("X-lynxus-trace-id"));
            assertEquals(List.of("request-1"), headers.get().get("X-lynxus-request-id"));
            assertFalse(headers.get().containsKey("Idempotency-key"));
            assertFalse(headers.get().containsKey("X-lynxus-extension-registration-id"));
            assertFalse(headers.get().containsKey("X-lynxus-extension-descriptor-type"));
            assertFalse(headers.get().containsKey("X-lynxus-extension-descriptor-id"));
            assertFalse(body.get().contains("accountId"));
            assertFalse(body.get().contains("idempotencyKey"));
            assertFalse(body.get().contains("externalSecretRef"));
            assertTrue(body.get().contains("\"credential\""));
            assertTrue(body.get().contains("secret-api-key"));

            client.invoke(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/private",
                "/credentials/validate",
                new RemoteCredentialLifecycleRequest(
                    Map.of("type", "TOOL_CONNECTOR", "id", "enterprise.acme.crm"),
                    Map.of("config", Map.of(), "externalSecretRef", "vault://opaque-ref"),
                    null,
                    Map.of("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
                ),
                "11111111111111111111111111111111",
                "request-2"
            );
            assertFalse(validateBody.get().contains("\"credential\""));
            assertTrue(validateBody.get().contains("\"externalSecretRef\""));
        } finally {
            server.stop(0);
        }

        JdkIntegrationCredentialLifecycleClient failed = new JdkIntegrationCredentialLifecycleClient(new ObjectMapper(), "internal-token");
        ApiProblemException failure = assertThrows(ApiProblemException.class, () -> failed.invoke(
            "http://127.0.0.1:1/private",
            "/credentials/create",
            new RemoteCredentialLifecycleRequest(
                Map.of("type", "TOOL_CONNECTOR", "id", "enterprise.acme.crm"),
                Map.of("config", Map.of()),
                Map.of("apiKey", "secret-api-key"),
                Map.of("traceparent", "00-11111111111111111111111111111111-2222222222222222-01")
            ),
            "11111111111111111111111111111111",
            "request-1"
        ));
        assertEquals("INTEGRATION_ACCOUNT_CREDENTIAL_REMOTE_FAILED", failure.code());
        assertFalse(failure.getMessage().contains("secret-api-key"));
        assertFalse(failure.getMessage().contains("127.0.0.1"));
    }

    @Test
    void createRequiresNameAndDefaultsNonCredentialLifecycleFields() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );

        assertThrows(IllegalArgumentException.class, () -> service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            " ",
            null,
            Map.of()
        )));

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            null,
            null
        ));

        assertEquals(IntegrationAccountSubjectType.TOOL_CONNECTOR, created.subjectType());
        assertEquals("simple-http", created.subjectId());
        assertEquals(IntegrationAccountStatus.ENABLED, created.status());
        assertEquals(IntegrationAccountCredentialStatus.NOT_CONFIGURED, created.credentialStatus());
        assertEquals(Map.of(), created.config());
        assertFalse(created.hasExternalSecretRef());
        assertFalse(created.credentialConfigured());
        assertEquals(Map.of(), saved.get().metadata());
        assertTrue(saved.get().name().length() <= 128);
    }

    @Test
    void accountAvailabilityHardBlocksSubjectMismatch() {
        StoredIntegrationAccount account = storedAccount(
            "integration-account-availability",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            "vault://must-not-leak",
            null,
            null,
            IntegrationAccountCredentialStatus.ACTIVE
        );
        IntegrationAccountService service = serviceWithAccount(account);

        var decision = service.evaluateAccountAvailability(
            account.id(),
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu"
        );
        assertFalse(decision.available());
        assertEquals(IntegrationAccountAvailabilityBlock.SUBJECT_MISMATCH, decision.hardBlock());
        assertEquals(List.of(), decision.risks());

        ApiProblemException error = assertThrows(ApiProblemException.class, () -> service.requireAccountAvailability(
            account.id(),
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu"
        ));
        assertEquals("INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED", error.code());
        assertFalse(error.getMessage().contains("vault://must-not-leak"));
    }

    @Test
    void accountAvailabilityHardBlocksNonEnabledAccountStatus() {
        for (IntegrationAccountStatus status : List.of(
            IntegrationAccountStatus.DISABLED,
            IntegrationAccountStatus.ARCHIVED
        )) {
            StoredIntegrationAccount account = storedAccount(
                "integration-account-" + status.name().toLowerCase(java.util.Locale.ROOT),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http",
                "Vendor Account",
                status,
                Map.of(),
                null,
                null,
                null,
                IntegrationAccountCredentialStatus.ACTIVE
            );
            IntegrationAccountService service = serviceWithAccount(account);

            var decision = service.evaluateAccountAvailability(
                account.id(),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http"
            );

            assertFalse(decision.available());
            assertEquals(IntegrationAccountAvailabilityBlock.ACCOUNT_STATUS_NOT_ENABLED, decision.hardBlock());
            assertThrows(ApiProblemException.class, () -> service.requireAccountAvailability(
                account.id(),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http"
            ));
        }
    }

    @Test
    void accountAvailabilityHardBlocksRevokedCredentialStates() {
        for (IntegrationAccountCredentialStatus credentialStatus : List.of(
            IntegrationAccountCredentialStatus.REVOKE_FAILED,
            IntegrationAccountCredentialStatus.REVOKED
        )) {
            StoredIntegrationAccount account = storedAccount(
                "integration-account-" + credentialStatus.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http",
                "Vendor Account",
                IntegrationAccountStatus.ENABLED,
                Map.of(),
                "vault://must-not-leak",
                null,
                null,
                credentialStatus
            );
            IntegrationAccountService service = serviceWithAccount(account);

            var decision = service.evaluateAccountAvailability(
                account.id(),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http"
            );

            assertFalse(decision.available());
            assertEquals(
                credentialStatus == IntegrationAccountCredentialStatus.REVOKE_FAILED
                    ? IntegrationAccountAvailabilityBlock.CREDENTIAL_REVOKE_FAILED
                    : IntegrationAccountAvailabilityBlock.CREDENTIAL_REVOKED,
                decision.hardBlock()
            );
            assertEquals(List.of(), decision.risks());
            ApiProblemException error = assertThrows(ApiProblemException.class, () -> service.requireAccountAvailability(
                account.id(),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http"
            ));
            assertEquals("INTEGRATION_ACCOUNT_AVAILABILITY_BLOCKED", error.code());
            assertFalse(error.getMessage().contains("vault://must-not-leak"));
        }
    }

    @Test
    void accountAvailabilityTreatsCredentialRiskStatesAsWarningsOnly() {
        StoredIntegrationAccount activeAccount = storedAccount(
            "integration-account-active",
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            IntegrationAccountStatus.ENABLED,
            Map.of(),
            null,
            null,
            null,
            IntegrationAccountCredentialStatus.ACTIVE
        );
        var activeDecision = serviceWithAccount(activeAccount).requireAccountAvailability(
            activeAccount.id(),
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http"
        );
        assertTrue(activeDecision.available());
        assertEquals(List.of(), activeDecision.risks());

        Map<IntegrationAccountCredentialStatus, IntegrationAccountAvailabilityRisk> expectedRisks = Map.of(
            IntegrationAccountCredentialStatus.NOT_CONFIGURED,
            IntegrationAccountAvailabilityRisk.CREDENTIAL_NOT_CONFIGURED,
            IntegrationAccountCredentialStatus.VALIDATION_FAILED,
            IntegrationAccountAvailabilityRisk.CREDENTIAL_VALIDATION_FAILED,
            IntegrationAccountCredentialStatus.ROTATION_REQUIRED,
            IntegrationAccountAvailabilityRisk.CREDENTIAL_ROTATION_REQUIRED
        );

        for (Map.Entry<IntegrationAccountCredentialStatus, IntegrationAccountAvailabilityRisk> entry : expectedRisks.entrySet()) {
            StoredIntegrationAccount account = storedAccount(
                "integration-account-" + entry.getKey().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http",
                "Vendor Account",
                IntegrationAccountStatus.ENABLED,
                Map.of(),
                null,
                null,
                null,
                entry.getKey()
            );
            IntegrationAccountService service = serviceWithAccount(account);

            var decision = service.requireAccountAvailability(
                account.id(),
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http"
            );

            assertTrue(decision.available());
            assertNull(decision.hardBlock());
            assertEquals(List.of(entry.getValue()), decision.risks());
        }
    }

    @Test
    void createAndUpdateValidateSubjectAndAccountConfigSchema() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            readyDefinitionService()
        );

        ApiProblemException missingSubject = assertThrows(ApiProblemException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "missing.connector",
                "Vendor Account",
                null,
                Map.of()
            )
        ));
        assertEquals("INTEGRATION_ACCOUNT_SUBJECT_NOT_FOUND", missingSubject.code());

        ApiProblemException invalidConfig = assertThrows(ApiProblemException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.TOOL_CONNECTOR,
                "simple-http",
                "Vendor Account",
                null,
                Map.of("tenantId", "")
            )
        ));
        assertEquals("INTEGRATION_ACCOUNT_CONFIG_INVALID", invalidConfig.code());

        var created = service.createAccount(new CreateIntegrationAccountRequest(
            IntegrationAccountSubjectType.TOOL_CONNECTOR,
            "simple-http",
            "Vendor Account",
            null,
            Map.of("tenantId", "tenant-001")
        ));
        service.updateAccount(created.id(), new UpdateIntegrationAccountRequest(
            "Renamed",
            IntegrationAccountStatus.DISABLED,
            Map.of("tenantId", "tenant-002")
        ));

        assertEquals("Renamed", saved.get().name());
        assertEquals(IntegrationAccountStatus.DISABLED, saved.get().status());
        assertEquals("tenant-002", saved.get().config().get("tenantId"));
    }

    @Test
    void registryNotReadyPreventsCreateBeforeSubjectLookup() {
        IntegrationAccountService service = new IntegrationAccountService(
            mock(IntegrationAccountRepository.class),
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            notReadyDefinitionService()
        );

        assertThrows(ExtensionDefinitionRegistryNotReadyException.class, () -> service.createAccount(
            new CreateIntegrationAccountRequest(
                IntegrationAccountSubjectType.CHANNEL_PROVIDER,
                "feishu",
                "Feishu Account",
                null,
                Map.of()
            )
        ));
    }

    @Test
    void archiveAndStatusUpdateDoNotTouchCredentialFields() {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        AtomicReference<StoredIntegrationAccount> saved = new AtomicReference<>();
        StoredIntegrationAccount existing = storedAccount(
            "integration-account-status",
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            "feishu",
            "Feishu Account",
            IntegrationAccountStatus.ENABLED,
            Map.of("tenantId", "tenant-001"),
            null,
            "ciphertext",
            "fingerprint"
        );
        saved.set(existing);
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(repository).saveAccount(any());
        when(repository.findAccount(any())).thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        IntegrationAccountService service = new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            readyDefinitionService()
        );

        var disabled = service.updateAccountStatus(existing.id(), new UpdateIntegrationAccountStatusRequest(IntegrationAccountStatus.DISABLED));
        assertEquals(IntegrationAccountStatus.DISABLED, disabled.status());
        assertEquals("ciphertext", saved.get().credentialCiphertext());
        assertEquals("fingerprint", saved.get().credentialFingerprint());

        var archived = service.archiveAccount(existing.id());
        assertEquals(IntegrationAccountStatus.ARCHIVED, archived.status());
        assertEquals("ciphertext", saved.get().credentialCiphertext());
        assertEquals("fingerprint", saved.get().credentialFingerprint());
    }

    private static ExtensionDefinitionService readyDefinitionService() {
        return definitionService(accountConfigSchema());
    }

    private static ExtensionDefinitionService emptySchemaDefinitionService() {
        return definitionService(Map.of("type", "object"));
    }

    private static ExtensionDefinitionService remoteDefinitionService() {
        ExtensionManifestFetcher fetcher = (manifestUrl, headers) -> {
            String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
            if (ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID.equals(registrationId)) {
                return manifest(List.of(channelProviderDescriptor("feishu", Map.of("type", "object"))), List.of());
            }
            if (ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID.equals(registrationId)) {
                return manifest(List.of(), List.of(
                    toolConnectorDescriptor("business-code-secret-http", Map.of("type", "object")),
                    toolConnectorDescriptor("mcp", Map.of("type", "object")),
                    toolConnectorDescriptor("simple-http", Map.of("type", "object"))
                ));
            }
            if ("acme-remote".equals(registrationId)) {
                return manifest(List.of(), List.of(remoteToolConnectorDescriptor()));
            }
            throw new AssertionError("unexpected manifest fetch for " + registrationId);
        };
        ExtensionDefinitionService service = new ExtensionDefinitionService(
            registrationServiceFromYaml("""
                lynxus:
                  extensions:
                    services:
                      - registrationId: acme-remote
                        baseUrl: https://remote.example.com/private
                        exposes:
                          toolConnectorTypes:
                            - enterprise.acme.crm
                        auth:
                          type: INTERNAL_TOKEN
                """),
            fetcher,
            "internal-token"
        );
        ExtensionDefinitionRegistry registry = service.loadRegistry();
        if (!registry.ready()) {
            throw new AssertionError(registry.errors().toString());
        }
        return service;
    }

    private static ExtensionDefinitionService definitionService(Map<String, Object> accountConfigSchema) {
        ExtensionDefinitionService service = new ExtensionDefinitionService(
            registrationService(),
            manifestFetcher(accountConfigSchema),
            "internal-token"
        );
        ExtensionDefinitionRegistry registry = service.loadRegistry();
        if (!registry.ready()) {
            throw new AssertionError(registry.errors().toString());
        }
        return service;
    }

    private static ExtensionDefinitionService notReadyDefinitionService() {
        return new ExtensionDefinitionService(
            registrationService(),
            (manifestUrl, headers) -> {
                throw new IOException("registry unavailable");
            },
            "internal-token"
        );
    }

    private static IntegrationAccountService serviceWithAccount(StoredIntegrationAccount account) {
        IntegrationAccountRepository repository = mock(IntegrationAccountRepository.class);
        when(repository.findAccount(account.id())).thenReturn(Optional.of(account));
        return new IntegrationAccountService(
            repository,
            new IntegrationCredentialCrypto(new ObjectMapper(), "test-encryption-key"),
            emptySchemaDefinitionService()
        );
    }

    private static ExtensionRegistrationService registrationService() {
        return new ExtensionRegistrationService(
            new ExtensionRegistrationProperties(null),
            "http://channel-gateway.example.com",
            "http://agent-runtime.example.com"
        );
    }

    private static ExtensionRegistrationService registrationServiceFromYaml(String operatorYaml) {
        try {
            Path tempFile = Files.createTempFile("lynxus-api-integration-registration", ".yaml");
            Files.writeString(tempFile, operatorYaml);
            return new ExtensionRegistrationService(
                new ExtensionRegistrationProperties(tempFile.toString()),
                "http://channel-gateway.example.com",
                "http://agent-runtime.example.com"
            );
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ExtensionManifestFetcher manifestFetcher(Map<String, Object> accountConfigSchema) {
        return (manifestUrl, headers) -> {
            String registrationId = headers.get(LynxusExtensionHeaders.REGISTRATION_ID);
            if (ExtensionRegistrationLoader.CORE_CHANNEL_GATEWAY_REGISTRATION_ID.equals(registrationId)) {
                return manifest(List.of(channelProviderDescriptor("feishu", accountConfigSchema)), List.of());
            }
            if (ExtensionRegistrationLoader.CORE_AGENT_RUNTIME_REGISTRATION_ID.equals(registrationId)) {
                return manifest(List.of(), List.of(
                    toolConnectorDescriptor("business-code-secret-http", accountConfigSchema),
                    toolConnectorDescriptor("mcp", accountConfigSchema),
                    toolConnectorDescriptor("simple-http", accountConfigSchema)
                ));
            }
            throw new AssertionError("unexpected manifest fetch for " + registrationId);
        };
    }

    private static String manifest(List<Map<String, Object>> channelProviders, List<Map<String, Object>> toolConnectors) {
        Map<String, Object> descriptors = new LinkedHashMap<>();
        descriptors.put("channelProviders", channelProviders);
        descriptors.put("toolConnectors", toolConnectors);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("extensionApiVersion", 1);
        manifest.put("coreMinVersion", "0.8.0");
        manifest.put("coreMaxVersion", "0.9.x");
        manifest.put("descriptors", descriptors);
        return new ObjectMapper().writeValueAsString(manifest);
    }

    private static Map<String, Object> channelProviderDescriptor(String providerType, Map<String, Object> accountConfigSchema) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("providerType", providerType);
        descriptor.put("title", "Feishu");
        descriptor.put("description", "Feishu description");
        descriptor.put("accountConfigSchema", accountConfigSchema);
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of("type", "object"));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("defaultConfig", Map.of());
        descriptor.put("jobDefinitions", List.of());
        descriptor.put("outbound", channelOutboundCapability());
        descriptor.put("endpoints", Map.of());
        return descriptor;
    }

    private static Map<String, Object> channelOutboundCapability() {
        return Map.of(
            "mode", "FRAME_STREAM",
            "supportsTyping", true,
            "supportsDraftUpdate", true,
            "supportsFinalDelivery", true,
            "supportsCredentialRef", false,
            "requiresIdempotentFinalDelivery", true
        );
    }

    private static Map<String, Object> toolConnectorDescriptor(String connectorType, Map<String, Object> accountConfigSchema) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("connectorType", connectorType);
        descriptor.put("title", "CRM");
        descriptor.put("description", "CRM description");
        descriptor.put("accountConfigSchema", accountConfigSchema);
        descriptor.put("accountConfigUiSchema", List.of());
        descriptor.put("configSchema", Map.of("type", "object"));
        descriptor.put("configUiSchema", List.of());
        descriptor.put("operationMappingSchema", Map.of("type", "object"));
        descriptor.put("operationMappingUiSchema", List.of());
        descriptor.put("endpoints", Map.of("invoke", "/tools/crm/invoke"));
        if ("simple-http".equals(connectorType)) {
            descriptor.put("credentialSchema", Map.of(
                "type", "object",
                "required", List.of("bearerToken"),
                "properties", Map.of("bearerToken", Map.of("type", "string", "minLength", 1)),
                "additionalProperties", false
            ));
            descriptor.put("credentialUiSchema", List.of());
        }
        if ("business-code-secret-http".equals(connectorType)) {
            descriptor.put("credentialSchema", Map.of(
                "type", "object",
                "required", List.of("businessCode", "secretKey"),
                "properties", Map.of(
                    "businessCode", Map.of("type", "string", "minLength", 1),
                    "secretKey", Map.of("type", "string", "minLength", 1)
                ),
                "additionalProperties", false
            ));
            descriptor.put("credentialUiSchema", List.of());
        }
        return descriptor;
    }

    private static Map<String, Object> remoteToolConnectorDescriptor() {
        Map<String, Object> descriptor = toolConnectorDescriptor("enterprise.acme.crm", Map.of("type", "object"));
        descriptor.put("credentialSchema", Map.of(
            "type", "object",
            "required", List.of("apiKey"),
            "properties", Map.of("apiKey", Map.of("type", "string", "minLength", 1)),
            "additionalProperties", false
        ));
        descriptor.put("credentialUiSchema", List.of());
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("invoke", "/tools/crm/invoke");
        endpoints.put("createCredential", "/credentials/create");
        endpoints.put("rotateCredential", "/credentials/rotate");
        endpoints.put("revokeCredential", "/credentials/revoke");
        endpoints.put("validateCredential", "/credentials/validate");
        descriptor.put("endpoints", endpoints);
        return descriptor;
    }

    private static Map<String, Object> accountConfigSchema() {
        return Map.of(
            "type", "object",
            "required", List.of("tenantId"),
            "properties", Map.of(
                "tenantId", Map.of("type", "string", "minLength", 1)
            ),
            "additionalProperties", false
        );
    }

    private static StoredIntegrationAccount storedAccount(
        String id,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        String externalSecretRef,
        String credentialCiphertext,
        String credentialFingerprint
    ) {
        return storedAccount(
            id,
            subjectType,
            subjectId,
            name,
            status,
            config,
            externalSecretRef,
            credentialCiphertext,
            credentialFingerprint,
            IntegrationAccountCredentialStatus.ACTIVE
        );
    }

    private static StoredIntegrationAccount storedAccount(
        String id,
        IntegrationAccountSubjectType subjectType,
        String subjectId,
        String name,
        IntegrationAccountStatus status,
        Map<String, Object> config,
        String externalSecretRef,
        String credentialCiphertext,
        String credentialFingerprint,
        IntegrationAccountCredentialStatus credentialStatus
    ) {
        assertNotNull(id);
        java.time.Instant now = java.time.Instant.now();
        return new StoredIntegrationAccount(
            id,
            subjectType,
            subjectId,
            name,
            status,
            config,
            externalSecretRef,
            credentialCiphertext,
            credentialFingerprint,
            credentialStatus,
            Map.of("ignored", true),
            now,
            now
        );
    }
}
