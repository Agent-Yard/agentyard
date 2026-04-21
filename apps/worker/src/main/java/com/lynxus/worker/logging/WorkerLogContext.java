package com.lynxus.worker.logging;

import com.lynxus.contracts.runtime.LogContextHeaders;
import com.lynxus.contracts.runtime.TraceContext;
import com.lynxus.contracts.runtime.WorkflowContracts.LogContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

public final class WorkerLogContext {
    public static final String TRACE_ID = "traceId";
    public static final String SPAN_ID = "spanId";
    public static final String SESSION_ID = "sessionId";
    public static final String WORKFLOW_ID = "workflowId";
    public static final String CUSTOMER_ID = "customerId";
    public static final String USER_ID = "userId";
    public static final String INSTANCE_ID = "instanceId";

    private WorkerLogContext() {
    }

    public static Scope open(LogContext context) {
        TraceContext traceContext = context != null && blankToNull(context.traceId()) != null
            ? new TraceContext(context.traceId(), TraceContext.generate().spanId(), "01")
            : TraceContext.generate();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(TRACE_ID, traceContext.traceId());
        values.put(SPAN_ID, traceContext.spanId());
        values.put(SESSION_ID, context == null ? null : context.sessionId());
        values.put(WORKFLOW_ID, context == null ? null : context.workflowId());
        values.put(CUSTOMER_ID, context == null ? null : context.customerId());
        values.put(USER_ID, context == null ? null : context.userId());
        values.put(INSTANCE_ID, resolveInstanceId());
        return new Scope(values);
    }

    public static Map<String, String> outboundHeaders(LogContext context) {
        Map<String, String> headers = new LinkedHashMap<>();
        TraceContext traceContext = currentTraceContext(context);
        headers.put(LogContextHeaders.TRACEPARENT, traceContext.toTraceparent());
        putHeader(headers, LogContextHeaders.SESSION_ID, valueOrCurrent(SESSION_ID, context == null ? null : context.sessionId()));
        putHeader(headers, LogContextHeaders.WORKFLOW_ID, valueOrCurrent(WORKFLOW_ID, context == null ? null : context.workflowId()));
        putHeader(headers, LogContextHeaders.CUSTOMER_ID, valueOrCurrent(CUSTOMER_ID, context == null ? null : context.customerId()));
        putHeader(headers, LogContextHeaders.USER_ID, valueOrCurrent(USER_ID, context == null ? null : context.userId()));
        return headers;
    }

    private static TraceContext currentTraceContext(LogContext context) {
        String traceId = valueOrCurrent(TRACE_ID, context == null ? null : context.traceId());
        String spanId = MDC.get(SPAN_ID);
        if (blankToNull(traceId) == null) {
            return TraceContext.generate();
        }
        if (blankToNull(spanId) == null) {
            return new TraceContext(traceId, TraceContext.generate().spanId(), "01");
        }
        return new TraceContext(traceId, spanId, "01");
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (blankToNull(value) != null) {
            headers.put(name, value);
        }
    }

    private static String valueOrCurrent(String key, String explicitValue) {
        return blankToNull(explicitValue) != null ? explicitValue : blankToNull(MDC.get(key));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String resolveInstanceId() {
        String instanceId = System.getenv("LYNXUS_INSTANCE_ID");
        return blankToNull(instanceId) == null ? "lynxus-worker-instance" : instanceId.trim();
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
