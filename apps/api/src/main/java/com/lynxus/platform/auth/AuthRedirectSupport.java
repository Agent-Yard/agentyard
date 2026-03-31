package com.lynxus.platform.auth;

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

    public String loginPagePath() {
        return "/login";
    }

    public String postLogoutRedirectUrl(jakarta.servlet.http.HttpServletRequest request, Authentication authentication) {
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
