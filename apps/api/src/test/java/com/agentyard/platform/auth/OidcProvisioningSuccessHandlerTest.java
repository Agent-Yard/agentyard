package com.agentyard.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentyard.platform.auth.AuthModels.AuthSource;
import com.agentyard.platform.auth.AuthModels.ExternalIdentity;
import com.agentyard.platform.auth.AuthModels.PlatformUser;
import com.agentyard.platform.auth.AuthModels.Role;
import com.agentyard.platform.auth.AuthModels.UserStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

class OidcProvisioningSuccessHandlerTest {
    @Test
    void shouldConsumeStoredReturnToOnAuthenticationSuccess() throws Exception {
        Authentication authentication = mock(Authentication.class);

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE, "/console/tasks?view=mine#open");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler("/", authentication).onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/console/tasks?view=mine#open");
        assertThat(session.getAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE)).isNull();
    }

    @Test
    void shouldRedirectToLoginSuccessPathWhenNoStoredReturnToExists() throws Exception {
        Authentication authentication = mock(Authentication.class);
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler("/console", authentication).onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/console");
        assertThat(session.getAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE)).isNull();
    }

    @Test
    void shouldRedirectToLoginSuccessPathAndClearUnsafeStoredReturnTo() throws Exception {
        Authentication authentication = mock(Authentication.class);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE, "https://evil.example.test/console");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler("/console", authentication).onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/console");
        assertThat(session.getAttribute(AuthRedirectSupport.RETURN_TO_SESSION_ATTRIBUTE)).isNull();
    }

    private OidcProvisioningSuccessHandler successHandler(String loginSuccessPath, Authentication authentication) {
        ExternalIdentityValidator externalIdentityValidator = mock(ExternalIdentityValidator.class);
        UserProvisioningService userProvisioningService = mock(UserProvisioningService.class);
        ExternalIdentity identity = externalIdentity();
        when(externalIdentityValidator.validate(authentication)).thenReturn(identity);
        when(userProvisioningService.provisionExternalUser(identity)).thenReturn(platformUser());
        return new OidcProvisioningSuccessHandler(
            externalIdentityValidator,
            userProvisioningService,
            redirectSupport(loginSuccessPath)
        );
    }

    private AuthRedirectSupport redirectSupport(String loginSuccessPath) {
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientRegistrationRepository> provider = mock(ObjectProvider.class);
        return new AuthRedirectSupport(
            new AuthProperties(new AuthProperties.Bootstrap("admin"), Role.BUSINESS_USER, true, loginSuccessPath),
            provider
        );
    }

    private ExternalIdentity externalIdentity() {
        return new ExternalIdentity(
            "https://idp.example.test",
            "subject-1",
            "alice",
            "Alice",
            "alice@example.test"
        );
    }

    private PlatformUser platformUser() {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        return new PlatformUser(
            "user-1",
            "alice",
            "Alice",
            "alice@example.test",
            AuthSource.EXTERNAL,
            "https://idp.example.test",
            "subject-1",
            UserStatus.ACTIVE,
            now,
            now,
            now,
            List.of(Role.BUSINESS_USER)
        );
    }
}
