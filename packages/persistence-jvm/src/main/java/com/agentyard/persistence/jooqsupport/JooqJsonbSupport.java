package com.agentyard.persistence.jooqsupport;

import java.util.Map;
import java.util.List;
import org.jooq.JSONB;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class JooqJsonbSupport {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> OBJECT_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JooqJsonbSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JSONB toJsonb(Object value) {
        return JSONB.jsonb(writeJson(value));
    }

    public String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize jsonb value", error);
        }
    }

    public Map<String, Object> readObjectMap(JSONB value) {
        if (value == null) {
            return Map.of();
        }
        String json = value.data();
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize jsonb object map", error);
        }
    }

    public List<Object> readList(JSONB value) {
        if (value == null) {
            return List.of();
        }
        String json = value.data();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_LIST);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize jsonb object list", error);
        }
    }

    public <T> T read(JSONB value, Class<T> type) {
        if (value == null) {
            return null;
        }
        return read(value.data(), type);
    }

    public <T> T read(JSONB value, TypeReference<T> type) {
        if (value == null) {
            return null;
        }
        return read(value.data(), type);
    }

    public <T> T read(String value, Class<T> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize jsonb value", error);
        }
    }

    public <T> T read(String value, TypeReference<T> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize jsonb value", error);
        }
    }
}
