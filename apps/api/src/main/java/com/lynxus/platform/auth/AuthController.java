package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.UserSession;
import com.lynxus.platform.shared.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/session")
    public ApiResponse<UserSession> session() {
        return ApiResponse.ok(authService.currentSession());
    }
}
