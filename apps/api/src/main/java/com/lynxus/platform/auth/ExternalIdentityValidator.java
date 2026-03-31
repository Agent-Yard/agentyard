package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.ExternalIdentity;

public interface ExternalIdentityValidator {
    ExternalIdentity validate(String credential);
}
