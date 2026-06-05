package com.agentyard.platform.auth;

import com.agentyard.platform.auth.AuthModels.ExternalIdentity;
import java.net.URL;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class OidcAuthenticationExternalIdentityValidator implements ExternalIdentityValidator {
    @Override
    public ExternalIdentity validate(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("authentication must be present");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof OidcUser oidcUser)) {
            throw new IllegalArgumentException("current authentication is not an OIDC user");
        }
        URL issuer = oidcUser.getIssuer();
        if (issuer == null) {
            throw new IllegalArgumentException("oidc issuer is missing");
        }
        String subject = oidcUser.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("oidc subject is missing");
        }
        return new ExternalIdentity(
            issuer.toString(),
            subject,
            firstNonBlank(
                oidcUser.getPreferredUsername(),
                oidcUser.getEmail(),
                oidcUser.getClaimAsString("preferred_username"),
                oidcUser.getName(),
                subject
            ),
            firstNonBlank(
                oidcUser.getFullName(),
                oidcUser.getFullName(),
                oidcUser.getClaimAsString("name"),
                oidcUser.getClaimAsString("display_name"),
                oidcUser.getPreferredUsername(),
                oidcUser.getEmail(),
                subject
            ),
            emptyToNull(oidcUser.getEmail())
        );
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
