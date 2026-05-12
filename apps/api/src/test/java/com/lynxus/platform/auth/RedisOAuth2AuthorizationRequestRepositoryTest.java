package com.lynxus.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import tools.jackson.databind.ObjectMapper;

class RedisOAuth2AuthorizationRequestRepositoryTest {
    private static final String REGISTRATION_ID_ATTRIBUTE = "registration_id";

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedisOAuth2AuthorizationRequestRepository repository = new RedisOAuth2AuthorizationRequestRepository(
        redisTemplate,
        new RedisJsonCodec(new ObjectMapper()),
        new RedisKeyspace("lynxus:test"),
        new OAuth2AuthorizationRequestStoreProperties(Duration.ofMinutes(7))
    );

    @Test
    void shouldSaveAuthorizationRequestInRedisWithoutCreatingHttpSession() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/oauth2/authorization/corp");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        repository.saveAuthorizationRequest(authorizationRequest(), servletRequest, servletResponse);

        assertThat(servletRequest.getSession(false)).isNull();
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
            eq("lynxus:test:auth:oauth2:authorization-request:f36b45ae818809ee24ae2489edabfe3cf2a12627b6929c07fc7a3b885d414d44"),
            payloadCaptor.capture(),
            eq(Duration.ofMinutes(7))
        );
        assertThat(payloadCaptor.getValue()).contains("state-1", "corp", "code-verifier-1");
    }

    @Test
    void shouldLoadAndRemoveAuthorizationRequestByState() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        MockHttpServletRequest initialRequest = new MockHttpServletRequest("GET", "/oauth2/authorization/corp");
        repository.saveAuthorizationRequest(authorizationRequest(), initialRequest, new MockHttpServletResponse());
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), payloadCaptor.capture(), any(Duration.class));
        when(valueOperations.get(keyCaptor.getValue())).thenReturn(payloadCaptor.getValue());
        MockHttpServletRequest callbackRequest = new MockHttpServletRequest("GET", "/login/oauth2/code/corp");
        callbackRequest.addParameter(OAuth2ParameterNames.STATE, "state-1");

        OAuth2AuthorizationRequest loaded = repository.loadAuthorizationRequest(callbackRequest);
        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(callbackRequest, new MockHttpServletResponse());

        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo("state-1");
        assertThat(loaded.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(loaded.getAttributes()).containsEntry(REGISTRATION_ID_ATTRIBUTE, "corp");
        assertThat(loaded.getAttributes()).containsEntry("code_verifier", "code-verifier-1");
        assertThat(removed).isNotNull();
        assertThat(removed.getClientId()).isEqualTo("client-id");
        verify(redisTemplate).delete(keyCaptor.getValue());
    }

    private static OAuth2AuthorizationRequest authorizationRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://idp.example.test/oauth2/authorize")
            .clientId("client-id")
            .redirectUri("https://lynxus.example.test/login/oauth2/code/corp")
            .scopes(Set.of("openid", "profile"))
            .state("state-1")
            .additionalParameters(Map.of("nonce", "nonce-1"))
            .attributes(attributes -> {
                attributes.put(REGISTRATION_ID_ATTRIBUTE, "corp");
                attributes.put("code_verifier", "code-verifier-1");
            })
            .build();
    }
}
