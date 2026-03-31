package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.ExternalIdentity;
import com.lynxus.platform.auth.AuthModels.PlatformUser;

public interface UserProvisioningService {
    PlatformUser provisionExternalUser(ExternalIdentity identity);
}
