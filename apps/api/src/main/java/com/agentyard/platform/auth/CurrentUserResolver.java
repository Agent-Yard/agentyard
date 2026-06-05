package com.agentyard.platform.auth;

import com.agentyard.platform.auth.AuthModels.PlatformUser;

public interface CurrentUserResolver {
    PlatformUser resolveCurrentUser();
}
