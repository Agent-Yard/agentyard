package com.agentyard.platform.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Component
public class AuthRedirectSupport {
    static final String RETURN_TO_SESSION_ATTRIBUTE = AuthRedirectSupport.class.getName() + ".RETURN_TO";

    private final AuthProperties authProperties;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;

    public AuthRedirectSupport(
        AuthProperties authProperties,
        ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider
    ) {
        this.authProperties = authProperties;
        this.clientRegistrationRepositoryProvider = clientRegistrationRepositoryProvider;
    }

    public String authorizationRequestPath() {
        return "/oauth2/authorization/" + primaryRegistrationId();
    }

    public String loginSuccessPath() {
        return authProperties.loginSuccessPath();
    }

    public void storeLoginReturnTo(HttpServletRequest request, String returnTo) {
        request.getSession(true).setAttribute(RETURN_TO_SESSION_ATTRIBUTE, sanitizeReturnTo(returnTo));
    }

    public String consumeLoginReturnTo(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return loginSuccessPath();
        }
        Object value = session.getAttribute(RETURN_TO_SESSION_ATTRIBUTE);
        session.removeAttribute(RETURN_TO_SESSION_ATTRIBUTE);
        if (!(value instanceof String returnTo)) {
            return loginSuccessPath();
        }
        return sanitizeReturnTo(returnTo);
    }

    public String sanitizeReturnTo(String returnTo) {
        if (!isSafeFrontendReturnTo(returnTo)) {
            return loginSuccessPath();
        }
        return returnTo;
    }

    public String loginPagePath() {
        return "/login";
    }

    public String postLogoutRedirectUrl(HttpServletRequest request, Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)
            || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return request.getContextPath() + loginPagePath();
        }
        ClientRegistrationRepository clientRegistrationRepository = clientRegistrationRepositoryProvider.getIfAvailable();
        if (clientRegistrationRepository == null) {
            return request.getContextPath() + loginPagePath();
        }
        ClientRegistration registration = clientRegistrationRepository.findByRegistrationId(oauth2Authentication.getAuthorizedClientRegistrationId());
        if (registration == null) {
            return request.getContextPath() + loginPagePath();
        }
        Object endSessionEndpoint = registration.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
        if (!(endSessionEndpoint instanceof String endpoint) || endpoint.isBlank()) {
            return request.getContextPath() + loginPagePath();
        }
        return ServletUriComponentsBuilder.fromUriString(endpoint)
            .queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue())
            .queryParam(
                "post_logout_redirect_uri",
                ServletUriComponentsBuilder.fromCurrentContextPath().path(loginPagePath()).build().toUriString()
            )
            .build(true)
            .toUriString();
    }

    private boolean isSafeFrontendReturnTo(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || containsControlCharacter(returnTo) || returnTo.contains("\\")) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(returnTo);
        } catch (URISyntaxException exception) {
            return false;
        }
        String rawPath = uri.getRawPath();
        String path = uri.getPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || rawPath == null || path == null) {
            return false;
        }
        if (!rawPath.startsWith("/") || rawPath.startsWith("//") || !path.startsWith("/") || path.startsWith("//")) {
            return false;
        }
        if (path.contains("\\")
            || containsControlCharacter(path)
            || containsControlCharacter(uri.getQuery())
            || containsControlCharacter(uri.getFragment())) {
            return false;
        }
        if (hasUnsafePathSegment(path)) {
            return false;
        }
        return path.equals("/") || path.equals("/console") || path.startsWith("/console/");
    }

    private boolean containsControlCharacter(String value) {
        return value != null && value.chars().anyMatch(Character::isISOControl);
    }

    private boolean hasUnsafePathSegment(String path) {
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private String primaryRegistrationId() {
        ClientRegistrationRepository clientRegistrationRepository = clientRegistrationRepositoryProvider.getIfAvailable();
        if (clientRegistrationRepository == null) {
            throw new IllegalStateException("OIDC client registration is not configured");
        }
        if (!(clientRegistrationRepository instanceof Iterable<?> registrations)) {
            throw new IllegalStateException("OIDC client registration repository is not iterable");
        }
        Iterator<?> iterator = registrations.iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("OIDC client registration is not configured");
        }
        Object first = iterator.next();
        if (!(first instanceof ClientRegistration clientRegistration)) {
            throw new IllegalStateException("OIDC client registration repository returned an unsupported entry");
        }
        return clientRegistration.getRegistrationId();
    }
}
