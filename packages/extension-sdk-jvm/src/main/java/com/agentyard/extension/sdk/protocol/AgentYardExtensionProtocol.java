package com.agentyard.extension.sdk.protocol;

public final class AgentYardExtensionProtocol {
    public static final int EXTENSION_API_VERSION = 1;

    public static final String EXTENSION_MANIFEST_PATH = "/extension/manifest";
    public static final String EXTENSION_HEALTH_PATH = "/extension/health";
    public static final String HEALTH_LIVE_PATH = "/health/live";
    public static final String HEALTH_READY_PATH = "/health/ready";
    public static final String CHANNEL_OUTBOUND_FRAME_SUBSCRIPTIONS_PATH = "/extension/channel/outbound-frame-subscriptions";
    public static final String CHANNEL_OUTBOUND_FRAMES_STREAM_PATH = "/extension/channel/outbound-frames/stream";
    public static final String CHANNEL_OUTBOUND_FRAMES_ACK_PATH = "/extension/channel/outbound-frames/ack";

    public static final String TOOL_CONNECTOR_INVOKE_ENDPOINT = "invoke";
    public static final String CHANNEL_PROVIDER_RUN_JOB_ENDPOINT = "runJob";
    public static final String CREATE_CREDENTIAL_ENDPOINT = "createCredential";
    public static final String ROTATE_CREDENTIAL_ENDPOINT = "rotateCredential";
    public static final String REVOKE_CREDENTIAL_ENDPOINT = "revokeCredential";
    public static final String VALIDATE_CREDENTIAL_ENDPOINT = "validateCredential";
    public static final String CREDENTIAL_LIFECYCLE_ENDPOINT_PROFILES_FIELD = "credentialLifecycleEndpointProfiles";
    public static final String CREDENTIAL_LIFECYCLE_ENDPOINT_PROFILE_FIELD = "credentialLifecycleEndpointProfile";

    private AgentYardExtensionProtocol() {}
}
