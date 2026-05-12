package com.lynxus.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class SessionRedisConfigurationTest {
    @Test
    void shouldUseRedisBackedHttpSessionsByDefault() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Properties properties = yaml.getObject();

        assertThat(properties)
            .isNotNull()
            .containsEntry("spring.session.store-type", "redis")
            .containsEntry("spring.session.redis.namespace", "${LYNXUS_SESSION_REDIS_NAMESPACE:lynxus:session:http}")
            .containsEntry("lynxus.auth.oauth2.authorization-request.ttl", "${LYNXUS_AUTH_OAUTH2_AUTHORIZATION_REQUEST_TTL:10m}");
    }
}
