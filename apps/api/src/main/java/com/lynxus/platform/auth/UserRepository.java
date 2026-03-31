package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.PlatformUser;
import java.util.Optional;

public interface UserRepository {
    Optional<PlatformUser> findByUsername(String username);

    Optional<PlatformUser> findByExternalIdentity(String externalIssuer, String externalSubject);

    Optional<PlatformUser> findById(String userId);

    PlatformUser save(PlatformUser user);
}
