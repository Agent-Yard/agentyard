package com.agentyard.platform.auth;

import java.net.URL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

@Configuration
public class SessionRedisConfiguration {
    @Bean(name = "springSessionDefaultRedisSerializer")
    RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        ClassLoader classLoader = SessionRedisConfiguration.class.getClassLoader();
        return GenericJacksonJsonRedisSerializer.builder()
            .enableSpringCacheNullValueSupport()
            .customize(mapperBuilder -> mapperBuilder.addModules(SecurityJacksonModules.getModules(
                classLoader,
                springSessionPolymorphicTypeValidatorBuilder()
            )))
            .build();
    }

    private BasicPolymorphicTypeValidator.Builder springSessionPolymorphicTypeValidatorBuilder() {
        return BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(URL.class);
    }
}
