package com.agentyard.platform.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OidcProvisioningSuccessHandler implements AuthenticationSuccessHandler {
    private final ExternalIdentityValidator externalIdentityValidator;
    private final UserProvisioningService userProvisioningService;
    private final AuthRedirectSupport authRedirectSupport;

    public OidcProvisioningSuccessHandler(
        ExternalIdentityValidator externalIdentityValidator,
        UserProvisioningService userProvisioningService,
        AuthRedirectSupport authRedirectSupport
    ) {
        this.externalIdentityValidator = externalIdentityValidator;
        this.userProvisioningService = userProvisioningService;
        this.authRedirectSupport = authRedirectSupport;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
        throws IOException, ServletException {
        userProvisioningService.provisionExternalUser(externalIdentityValidator.validate(authentication));
        response.sendRedirect(authRedirectSupport.consumeLoginReturnTo(request));
    }
}
