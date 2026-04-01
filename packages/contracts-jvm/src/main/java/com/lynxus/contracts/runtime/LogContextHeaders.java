package com.lynxus.contracts.runtime;

public final class LogContextHeaders {
    public static final String TRACEPARENT = "traceparent";
    public static final String SESSION_ID = "X-Lynxus-Session-Id";
    public static final String WORKFLOW_ID = "X-Lynxus-Workflow-Id";
    public static final String CUSTOMER_ID = "X-Lynxus-Customer-Id";
    public static final String USER_ID = "X-Lynxus-User-Id";

    private LogContextHeaders() {
    }
}
