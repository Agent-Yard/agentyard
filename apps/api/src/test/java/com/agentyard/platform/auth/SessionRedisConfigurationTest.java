package com.agentyard.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class SessionRedisConfigurationTest {
    @Test
    void shouldUseRedisBackedHttpSessionsByDefault() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));

        Properties properties = yaml.getObject();

        assertThat(properties)
            .isNotNull()
            .containsEntry("spring.session.store-type", "redis")
            .containsEntry("spring.session.redis.namespace", "${AGENTYARD_SESSION_REDIS_NAMESPACE:agentyard:session:http}");
    }

    @Test
    void shouldDeserializeOidcSecurityContextWithUrlIssuerClaim() throws Exception {
        RedisSerializer<Object> serializer = new SessionRedisConfiguration().springSessionDefaultRedisSerializer();
        Instant issuedAt = Instant.parse("2026-05-12T00:00:00Z");
        OidcIdToken idToken = new OidcIdToken(
            "id-token",
            issuedAt,
            issuedAt.plusSeconds(3600),
            Map.of(
                IdTokenClaimNames.ISS,
                URI.create("https://issuer.example.test").toURL(),
                IdTokenClaimNames.SUB,
                "subject-1"
            )
        );
        OidcUserAuthority authority = new OidcUserAuthority(idToken);
        DefaultOidcUser principal = new DefaultOidcUser(Set.of(authority), idToken);
        SecurityContext source = new SecurityContextImpl(new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "corp"));

        Object restored = serializer.deserialize(serializer.serialize(source));

        assertThat(restored).isInstanceOf(SecurityContextImpl.class);
        assertThat(((SecurityContext) restored).getAuthentication()).isInstanceOf(OAuth2AuthenticationToken.class);
    }
}
