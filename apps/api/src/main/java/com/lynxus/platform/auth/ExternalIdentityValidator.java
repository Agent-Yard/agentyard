package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.ExternalIdentity;
import org.springframework.security.core.Authentication;

public interface ExternalIdentityValidator {
    ExternalIdentity validate(Authentication authentication);
}
