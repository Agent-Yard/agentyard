package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.PlatformUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserResolver implements CurrentUserResolver {
    private final UserRepository userRepository;
    private final UserProvisioningService userProvisioningService;
    private final ExternalIdentityValidator externalIdentityValidator;

    public SecurityContextCurrentUserResolver(
        UserRepository userRepository,
        UserProvisioningService userProvisioningService,
        ExternalIdentityValidator externalIdentityValidator
    ) {
        this.userRepository = userRepository;
        this.userProvisioningService = userProvisioningService;
        this.externalIdentityValidator = externalIdentityValidator;
    }

    @Override
    public PlatformUser resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new IllegalStateException("current user is not authenticated");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof String username && !username.isBlank()) {
            return loadLocalUser(username);
        }
        return userProvisioningService.provisionExternalUser(externalIdentityValidator.validate(authentication));
    }

    private PlatformUser loadLocalUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalStateException("current user not found: " + username));
    }
}
