package com.lynxus.platform.auth;

import com.lynxus.platform.auth.AuthModels.LogoutResponse;
import com.lynxus.platform.auth.AuthModels.UserSession;
import com.lynxus.platform.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final AuthProperties authProperties;
    private final AuthRedirectSupport authRedirectSupport;
    private final SecurityContextRepository securityContextRepository;

    public AuthController(
        AuthService authService,
        AuthProperties authProperties,
        AuthRedirectSupport authRedirectSupport,
        SecurityContextRepository securityContextRepository
    ) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.authRedirectSupport = authRedirectSupport;
        this.securityContextRepository = securityContextRepository;
    }

    @GetMapping("/login")
    public void login(
        @RequestParam(required = false) String returnTo,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws IOException {
        authRedirectSupport.storeLoginReturnTo(request, returnTo);
        redirectRelative(response, authRedirectSupport.authorizationRequestPath());
    }

    @GetMapping("/dev-bootstrap-login")
    public void devBootstrapLogin(
        @RequestParam(required = false) String returnTo,
        HttpServletRequest request,
        HttpServletResponse response
    ) throws IOException {
        if (!authProperties.devBootstrapEnabled()) {
            throw new NoSuchElementException("development bootstrap login is not enabled");
        }
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
            authProperties.bootstrap().username(),
            "N/A",
            List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_USER"))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        redirectRelative(response, authRedirectSupport.sanitizeReturnTo(returnTo));
    }

    @GetMapping("/session")
    @RequireRuntimeAccess
    public ApiResponse<UserSession> session() {
        return ApiResponse.ok(authService.currentSession());
    }

    @PostMapping("/logout")
    public ApiResponse<LogoutResponse> logout(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) {
        String postLogoutRedirectUrl = authRedirectSupport.postLogoutRedirectUrl(request, authentication);
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        SecurityContextHolder.clearContext();
        return ApiResponse.ok(new LogoutResponse(postLogoutRedirectUrl));
    }

    private void redirectRelative(HttpServletResponse response, String location) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, location);
    }
}
