package com.agentyard.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentyard.platform.auth.AuthModels.ExternalIdentity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class OidcAuthenticationExternalIdentityValidatorTest {
    @Test
    void shouldExtractIssuerSubjectAndProfileFields() {
        OidcAuthenticationExternalIdentityValidator validator = new OidcAuthenticationExternalIdentityValidator();
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        OidcIdToken idToken = new OidcIdToken(
            "token",
            now,
            now.plusSeconds(300),
            Map.of(
                IdTokenClaimNames.ISS, "https://issuer.example.com",
                IdTokenClaimNames.SUB, "sub-1",
                "preferred_username", "alice",
                "name", "Alice"
            )
        );
        OidcUser oidcUser = new DefaultOidcUser(List.of(), idToken);

        ExternalIdentity identity = validator.validate(UsernamePasswordAuthenticationToken.authenticated(oidcUser, "N/A", List.of()));

        assertEquals("https://issuer.example.com", identity.issuer());
        assertEquals("sub-1", identity.subject());
        assertEquals("alice", identity.preferredUsername());
        assertEquals("Alice", identity.displayName());
    }
}
