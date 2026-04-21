package com.lynxus.shared.redis;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class RedisJsonCodec {
    private final ObjectMapper objectMapper;

    public RedisJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize redis payload", error);
        }
    }

    public <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize redis payload", error);
        }
    }

    public <T> T read(String payload, TypeReference<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to deserialize redis payload", error);
        }
    }
}
