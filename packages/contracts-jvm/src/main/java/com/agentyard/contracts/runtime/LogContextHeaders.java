package com.agentyard.contracts.runtime;

public final class LogContextHeaders {
    public static final String TRACEPARENT = "traceparent";
    public static final String SESSION_ID = "X-AgentYard-Session-Id";
    public static final String WORKFLOW_ID = "X-AgentYard-Workflow-Id";
    public static final String CUSTOMER_ID = "X-AgentYard-Customer-Id";
    public static final String USER_ID = "X-AgentYard-User-Id";

    private LogContextHeaders() {
    }
}
