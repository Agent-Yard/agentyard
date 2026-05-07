package com.example.lynxus.extensiontemplate.extension;

import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExtensionEndpointPaths {
    public static final String DEFAULT_CREDENTIAL_LIFECYCLE_PROFILE = "default";

    public static final String TOOL_INVOKE = "/tools/invoke";
    public static final String CHANNEL_RUN_JOB = "/channel/run-job";

    public static final String CREDENTIAL_CREATE = "/credentials";
    public static final String CREDENTIAL_ROTATE = "/credentials/rotate";
    public static final String CREDENTIAL_REVOKE = "/credentials/revoke";
    public static final String CREDENTIAL_VALIDATE = "/credentials/validate";

    private ExtensionEndpointPaths() {}

    public static Map<String, Object> defaultCredentialLifecycleEndpointProfile() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put(LynxusExtensionProtocol.CREATE_CREDENTIAL_ENDPOINT, CREDENTIAL_CREATE);
        endpoints.put(LynxusExtensionProtocol.ROTATE_CREDENTIAL_ENDPOINT, CREDENTIAL_ROTATE);
        endpoints.put(LynxusExtensionProtocol.REVOKE_CREDENTIAL_ENDPOINT, CREDENTIAL_REVOKE);
        endpoints.put(LynxusExtensionProtocol.VALIDATE_CREDENTIAL_ENDPOINT, CREDENTIAL_VALIDATE);
        return Map.copyOf(endpoints);
    }
}
