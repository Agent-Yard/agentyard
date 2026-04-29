package com.lynxus.extension.sdk.protocol;

import java.util.Set;

public final class LynxusExtensionHeaders {
    public static final String AUTHORIZATION = "Authorization";
    public static final String REGISTRATION_ID = "X-Lynxus-Extension-Registration-Id";
    public static final String DESCRIPTOR_TYPE = "X-Lynxus-Extension-Descriptor-Type";
    public static final String DESCRIPTOR_ID = "X-Lynxus-Extension-Descriptor-Id";
    public static final String TRACE_ID = "X-Lynxus-Trace-Id";
    public static final String REQUEST_ID = "X-Lynxus-Request-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    public static final Set<String> SERVICE_LEVEL_REQUIRED_HEADERS = Set.of(AUTHORIZATION);
    public static final Set<String> SERVICE_LEVEL_OPTIONAL_HEADERS = Set.of(REGISTRATION_ID);
    public static final Set<String> SERVICE_LEVEL_HEADERS = Set.of(AUTHORIZATION, REGISTRATION_ID);

    public static final Set<String> DESCRIPTOR_LEVEL_REQUIRED_HEADERS = Set.of(
        AUTHORIZATION,
        REGISTRATION_ID,
        DESCRIPTOR_TYPE,
        DESCRIPTOR_ID,
        TRACE_ID,
        REQUEST_ID,
        IDEMPOTENCY_KEY
    );

    public static final Set<String> CREDENTIAL_LIFECYCLE_REQUIRED_HEADERS = Set.of(
        AUTHORIZATION,
        TRACE_ID,
        REQUEST_ID
    );

    private LynxusExtensionHeaders() {}
}
