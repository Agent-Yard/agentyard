package com.agentyard.contracts.agent;

import java.util.Map;

/** A verified adapter callback, never an Agent-authored tool invocation. */
public final class ToolRunCallbacks {
    private ToolRunCallbacks() {}
    public record Source(String subjectType,String descriptorId,String accountId) {}
    public record Callback(String waitingId,String eventId,Source source,Map<String,Object> payload) {
        public Callback { payload=payload==null?Map.of():java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload)); }
    }
}
