package com.lynxus.channel.gateway.channel;

import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelTraceContext;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

record TraceIds(String traceId, String traceparent, String requestId, String tracestate) {
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$");

    static TraceIds create() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new TraceIds(traceId, "00-" + traceId + "-" + spanId + "-01", nextRequestId(), null);
    }

    static TraceIds fromOrCreate(NormalizedChannelTraceContext traceContext) {
        if (traceContext == null || traceContext.traceparent() == null || traceContext.traceparent().isBlank()) {
            return create();
        }
        String traceparent = traceContext.traceparent().trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher matcher = TRACEPARENT.matcher(traceparent);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("traceContext.traceparent is invalid");
        }
        return new TraceIds(matcher.group(1), traceparent, nextRequestId(), traceContext.tracestate());
    }

    static String nextRequestId() {
        return "request-" + UUID.randomUUID();
    }

    NormalizedChannelTraceContext traceContext() {
        return new NormalizedChannelTraceContext(traceparent, tracestate);
    }
}
