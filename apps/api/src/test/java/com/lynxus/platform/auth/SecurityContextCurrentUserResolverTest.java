package com.lynxus.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.ExternalIdentity;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityContextCurrentUserResolverTest {
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldResolveBootstrapPrincipalFromRepository() {
        UserRepository userRepository = mock(UserRepository.class);
        UserProvisioningService provisioningService = mock(UserProvisioningService.class);
        ExternalIdentityValidator externalIdentityValidator = mock(ExternalIdentityValidator.class);
        PlatformUser bootstrapUser = platformUser("user-admin", "admin", AuthSource.LOCAL_BOOTSTRAP, null, null);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(bootstrapUser));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            new BootstrapPrincipal("admin"),
            "N/A",
            List.of()
        ));

        SecurityContextCurrentUserResolver resolver = new SecurityContextCurrentUserResolver(
            userRepository,
            provisioningService,
            externalIdentityValidator
        );

        PlatformUser resolved = resolver.resolveCurrentUser();

        assertEquals("user-admin", resolved.id());
    }

    @Test
    void shouldProvisionOidcUserFromSecurityContext() {
        UserRepository userRepository = mock(UserRepository.class);
        UserProvisioningService provisioningService = mock(UserProvisioningService.class);
        ExternalIdentityValidator externalIdentityValidator = mock(ExternalIdentityValidator.class);
        ExternalIdentity identity = new ExternalIdentity("https://issuer.example.com", "sub-1", "alice", "Alice", "alice@example.com");
        PlatformUser externalUser = platformUser("user-ext", "alice", AuthSource.EXTERNAL, identity.issuer(), identity.subject());
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(new Object(), "N/A", List.of());
        when(externalIdentityValidator.validate(authentication)).thenReturn(identity);
        when(provisioningService.provisionExternalUser(identity)).thenReturn(externalUser);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        SecurityContextCurrentUserResolver resolver = new SecurityContextCurrentUserResolver(
            userRepository,
            provisioningService,
            externalIdentityValidator
        );

        PlatformUser resolved = resolver.resolveCurrentUser();

        assertEquals("user-ext", resolved.id());
        verify(provisioningService).provisionExternalUser(identity);
    }

    private PlatformUser platformUser(String id, String username, AuthSource authSource, String externalIssuer, String externalSubject) {
        Instant now = Instant.parse("2026-03-31T00:00:00Z");
        return new PlatformUser(
            id,
            username,
            username,
            null,
            authSource,
            externalIssuer,
            externalSubject,
            UserStatus.ACTIVE,
            now,
            now,
            null,
            List.of(Role.PLATFORM_ADMIN)
        );
    }
}
