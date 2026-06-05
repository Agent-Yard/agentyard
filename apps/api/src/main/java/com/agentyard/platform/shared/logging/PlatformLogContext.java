package com.agentyard.platform.shared.logging;

import com.agentyard.contracts.runtime.LogContextHeaders;
import com.agentyard.contracts.runtime.TraceContext;
import com.agentyard.contracts.runtime.WorkflowContracts.LogContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

public final class PlatformLogContext {
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String SESSION_ID = "sessionId";
    public static final String WORKFLOW_ID = "workflowId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String USER_ID = "userId";

    private PlatformLogContext() {
    }

    public static Scope open(TraceContext traceContext, String sessionId, String workflowId, String customerId, String userId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(TRACE_ID, traceContext.traceId());
        values.put(SPAN_ID, traceContext.spanId());
        values.put(SESSION_ID, sessionId);
        values.put(WORKFLOW_ID, workflowId);
        values.put(CUSTOMER_ID, customerId);
        values.put(USER_ID, userId);
        return new Scope(values);
    }

    public static Scope openBusiness(String sessionId, String workflowId, String customerId) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(SESSION_ID, sessionId);
        values.put(WORKFLOW_ID, workflowId);
        values.put(CUSTOMER_ID, customerId);
        return new Scope(values);
    }

    public static String current(String key) {
        return blankToNull(MDC.get(key));
    }

    public static LogContext capture(String sessionId, String workflowId, String customerId) {
        return new LogContext(
            current(TRACE_ID),
            blankToNull(sessionId) != null ? sessionId : current(SESSION_ID),
            blankToNull(workflowId) != null ? workflowId : current(WORKFLOW_ID),
            blankToNull(customerId) != null ? customerId : current(CUSTOMER_ID),
            current(USER_ID)
        );
    }

    public static Map<String, String> outboundHeaders(String sessionId, String workflowId, String customerId, String userId) {
        Map<String, String> headers = new LinkedHashMap<>();
        TraceContext traceContext = currentTraceContext();
        headers.put(LogContextHeaders.TRACEPARENT, traceContext.toTraceparent());
        putHeader(headers, LogContextHeaders.SESSION_ID, valueOrCurrent(SESSION_ID, sessionId));
        putHeader(headers, LogContextHeaders.WORKFLOW_ID, valueOrCurrent(WORKFLOW_ID, workflowId));
        putHeader(headers, LogContextHeaders.CUSTOMER_ID, valueOrCurrent(CUSTOMER_ID, customerId));
        putHeader(headers, LogContextHeaders.USER_ID, valueOrCurrent(USER_ID, userId));
        return headers;
    }

    public static TraceContext currentTraceContext() {
        String traceId = current(TRACE_ID);
        String spanId = current(SPAN_ID);
        if (traceId == null || spanId == null) {
            return TraceContext.generate();
        }
        return new TraceContext(traceId, spanId, "01");
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (blankToNull(value) != null) {
            headers.put(name, value);
        }
    }

    private static String valueOrCurrent(String key, String explicitValue) {
        return blankToNull(explicitValue) != null ? explicitValue : current(key);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previousValues = new LinkedHashMap<>();

        private Scope(Map<String, String> values) {
            values.forEach((key, value) -> {
                previousValues.put(key, MDC.get(key));
                if (value == null || value.isBlank()) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }

        @Override
        public void close() {
            previousValues.forEach((key, value) -> {
                if (value == null || value.isBlank()) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
