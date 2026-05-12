package com.lynxus.platform.auth;

import com.lynxus.shared.redis.RedisJsonCodec;
import com.lynxus.shared.redis.RedisKeyspace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

@Component
public class RedisOAuth2AuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final StringRedisTemplate redisTemplate;
    private final RedisJsonCodec codec;
    private final RedisKeyspace keyspace;
    private final OAuth2AuthorizationRequestStoreProperties properties;

    public RedisOAuth2AuthorizationRequestRepository(
        StringRedisTemplate redisTemplate,
        RedisJsonCodec codec,
        RedisKeyspace keyspace,
        OAuth2AuthorizationRequestStoreProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.keyspace = keyspace;
        this.properties = properties;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        String state = stateFromRequest(request);
        if (state == null) {
            return null;
        }
        String payload = redisTemplate.opsForValue().get(key(state));
        if (payload == null || payload.isBlank()) {
            return null;
        }
        return codec.read(payload, StoredAuthorizationRequest.class).toAuthorizationRequest();
    }

    @Override
    public void saveAuthorizationRequest(
        OAuth2AuthorizationRequest authorizationRequest,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (authorizationRequest == null) {
            removeAuthorizationRequest(request, response);
            return;
        }
        String state = normalizeState(authorizationRequest.getState());
        if (state == null) {
            throw new IllegalArgumentException("OAuth2 authorization request state must not be empty");
        }
        redisTemplate.opsForValue().set(key(state), codec.write(StoredAuthorizationRequest.from(authorizationRequest)), properties.ttl());
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        String state = stateFromRequest(request);
        if (state != null) {
            redisTemplate.delete(key(state));
        }
        return authorizationRequest;
    }

    private String key(String state) {
        return keyspace.oauth2AuthorizationRequest(sha256Hex(state));
    }

    private static String stateFromRequest(HttpServletRequest request) {
        return normalizeState(request.getParameter(OAuth2ParameterNames.STATE));
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return state.trim();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 digest is not available", error);
        }
    }

    public record StoredAuthorizationRequest(
        String authorizationUri,
        String clientId,
        String redirectUri,
        Set<String> scopes,
        String state,
        Map<String, Object> additionalParameters,
        String authorizationRequestUri,
        Map<String, Object> attributes
    ) {
        static StoredAuthorizationRequest from(OAuth2AuthorizationRequest request) {
            return new StoredAuthorizationRequest(
                request.getAuthorizationUri(),
                request.getClientId(),
                request.getRedirectUri(),
                request.getScopes() == null ? Set.of() : new LinkedHashSet<>(request.getScopes()),
                request.getState(),
                request.getAdditionalParameters() == null ? Map.of() : new LinkedHashMap<>(request.getAdditionalParameters()),
                request.getAuthorizationRequestUri(),
                request.getAttributes() == null ? Map.of() : new LinkedHashMap<>(request.getAttributes())
            );
        }

        OAuth2AuthorizationRequest toAuthorizationRequest() {
            OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(authorizationUri)
                .clientId(clientId)
                .redirectUri(redirectUri)
                .scopes(scopes == null ? Set.of() : scopes)
                .state(state)
                .additionalParameters(additionalParameters == null ? Map.of() : additionalParameters)
                .attributes(attributes == null ? Map.of() : attributes);
            if (authorizationRequestUri != null && !authorizationRequestUri.isBlank()) {
                builder.authorizationRequestUri(authorizationRequestUri);
            }
            return builder.build();
        }
    }
}
