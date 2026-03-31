package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.AuthSource;
import com.lynxus.platform.auth.AuthModels.ExternalIdentity;
import com.lynxus.platform.auth.AuthModels.PlatformUser;
import com.lynxus.platform.auth.AuthModels.Role;
import com.lynxus.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DefaultUserProvisioningService implements UserProvisioningService {
    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    public DefaultUserProvisioningService(UserRepository userRepository, AuthProperties authProperties) {
        this.userRepository = userRepository;
        this.authProperties = authProperties;
    }

    @Override
    public PlatformUser provisionExternalUser(ExternalIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw new IllegalArgumentException("external identity subject must not be blank");
        }

        return userRepository.findByExternalSubject(identity.subject())
            .map(existing -> updateExistingUser(existing, identity))
            .orElseGet(() -> createUser(identity));
    }

    private PlatformUser updateExistingUser(PlatformUser existing, ExternalIdentity identity) {
        Instant now = Instant.now();
        Set<Role> roles = new LinkedHashSet<>(existing.roles());
        roles.add(authProperties.defaultRole());
        List<Role> updatedRoles = AuthModels.orderedRoles(List.copyOf(roles));
        return userRepository.save(new PlatformUser(
            existing.id(),
            chooseExistingOrSuggestedUsername(existing.username(), identity.preferredUsername()),
            preferredDisplayName(identity.displayName(), existing.displayName()),
            firstNonBlank(identity.email(), existing.email()),
            AuthSource.EXTERNAL,
            identity.subject(),
            existing.status(),
            existing.createdAt(),
            now,
            existing.lastLoginAt(),
            updatedRoles
        ));
    }

    private PlatformUser createUser(ExternalIdentity identity) {
        Instant now = Instant.now();
        String username = allocateUsername(identity.preferredUsername(), identity.subject());
        return userRepository.save(new PlatformUser(
            "user-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
            username,
            preferredDisplayName(identity.displayName(), username),
            emptyToNull(identity.email()),
            AuthSource.EXTERNAL,
            identity.subject(),
            UserStatus.ACTIVE,
            now,
            now,
            null,
            List.of(authProperties.defaultRole())
        ));
    }

    private String allocateUsername(String preferredUsername, String subject) {
        String base = sanitizeUsername(firstNonBlank(preferredUsername, subject));
        String candidate = base;
        int suffix = 1;
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = base + suffix;
            suffix += 1;
        }
        return candidate;
    }

    private String chooseExistingOrSuggestedUsername(String existing, String suggested) {
        if (suggested == null || suggested.isBlank() || existing.equals(sanitizeUsername(suggested))) {
            return existing;
        }
        String sanitized = sanitizeUsername(suggested);
        return userRepository.findByUsername(sanitized)
            .isPresent() ? existing : sanitized;
    }

    private String sanitizeUsername(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        normalized = normalized.replaceAll("-{2,}", "-").replaceAll("^[.-]+|[.-]+$", "");
        return normalized.isBlank() ? "user" : normalized;
    }

    private String preferredDisplayName(String preferred, String fallback) {
        String value = firstNonBlank(preferred, fallback);
        return value == null || value.isBlank() ? "外部用户" : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
