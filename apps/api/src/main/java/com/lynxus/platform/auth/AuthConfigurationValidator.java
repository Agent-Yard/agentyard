package com.lynxus.platform.auth;

import java.util.Iterator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

@Component
public class AuthConfigurationValidator {
    public AuthConfigurationValidator(
        AuthProperties authProperties,
        ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider
    ) {
        if (!authProperties.devBootstrapEnabled() && !hasClientRegistrations(clientRegistrationRepositoryProvider.getIfAvailable())) {
            throw new IllegalStateException("OIDC client registration must be configured when development bootstrap login is disabled");
        }
    }

    private boolean hasClientRegistrations(ClientRegistrationRepository repository) {
        if (!(repository instanceof Iterable<?> iterable)) {
            return false;
        }
        Iterator<?> iterator = iterable.iterator();
        return iterator.hasNext();
    }
}
