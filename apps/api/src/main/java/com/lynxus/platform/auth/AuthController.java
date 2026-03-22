package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.SwitchRoleRequest;
import com.lynxus.platform.shared.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public ApiResponse<?> session() {
        return ApiResponse.ok(authService.currentSession());
    }

    @PatchMapping("/switch-role")
    public ApiResponse<?> switchRole(@Valid @RequestBody SwitchRoleRequest request) {
        return ApiResponse.ok(authService.switchRole(request.role()));
    }
}
