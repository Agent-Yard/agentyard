package com.lynxus.platform.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;

@Configuration
public class SessionRedisConfiguration {
    @Bean(name = "springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        ClassLoader classLoader = SessionRedisConfiguration.class.getClassLoader();
        return GenericJacksonJsonRedisSerializer.builder()
            .enableSpringCacheNullValueSupport()
            .customize(mapperBuilder -> mapperBuilder.addModules(SecurityJacksonModules.getModules(classLoader)))
            .build();
    }
}
