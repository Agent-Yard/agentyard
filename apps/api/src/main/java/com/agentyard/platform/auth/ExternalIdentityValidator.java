package com.agentyard.platform.auth;

import com.agentyard.platform.auth.AuthModels.ExternalIdentity;
import org.springframework.security.core.Authentication;

public interface ExternalIdentityValidator {
    ExternalIdentity validate(Authentication authentication);
}
