package com.lynxus.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson2.SecurityJackson2Modules;

@Configuration
public class SessionRedisConfiguration {
    @Bean(name = "springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        ObjectMapper sessionObjectMapper = new ObjectMapper();
        SecurityJackson2Modules.enableDefaultTyping(sessionObjectMapper);
        sessionObjectMapper.registerModules(SecurityJackson2Modules.getModules(
            SessionRedisConfiguration.class.getClassLoader()
        ));
        GenericJackson2JsonRedisSerializer.registerNullValueSerializer(sessionObjectMapper, null);
        return new GenericJackson2JsonRedisSerializer(sessionObjectMapper);
    }
}
