package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.PlatformUser;

public interface CurrentUserResolver {
    PlatformUser resolveCurrentUser();
}
