package com.agentyard.platform.auth;

import com.agentyard.platform.auth.AuthModels.ExternalIdentity;
import com.agentyard.platform.auth.AuthModels.PlatformUser;

public interface UserProvisioningService {
    PlatformUser provisionExternalUser(ExternalIdentity identity);
}
