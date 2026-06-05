package com.agentyard.platform.auth;

import com.agentyard.platform.shared.logging.ApiLogContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AuthSecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AuthSecurityConfiguration.class);
    private static final String OAUTH2_FAILURE_REDIRECT_URL = "/login?error";
    private static final int MAX_LOG_VALUE_LENGTH = 512;

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider,
        OidcProvisioningSuccessHandler oidcProvisioningSuccessHandler,
        SecurityContextRepository securityContextRepository,
        ApiLogContextFilter apiLogContextFilter,
        ObjectMapper objectMapper
    ) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .requestCache(requestCache -> requestCache.requestCache(new NullRequestCache()))
            .addFilterAfter(apiLogContextFilter, SecurityContextHolderFilter.class)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/api/system/health",
                    "/api/system/health/live",
                    "/api/system/health/ready",
                    "/api/auth/login",
                    "/api/auth/dev-bootstrap-login",
                    "/api/internal/**",
                    "/oauth2/authorization/**",
                    "/login/oauth2/code/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .defaultAuthenticationEntryPointFor(
                    problemAuthenticationEntryPoint(objectMapper),
                    request -> request.getRequestURI().startsWith("/api/")
                )
                .defaultAccessDeniedHandlerFor(
                    problemAccessDeniedHandler(objectMapper),
                    request -> request.getRequestURI().startsWith("/api/")
                )
            );

        if (clientRegistrationRepositoryProvider.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2
                .successHandler(oidcProvisioningSuccessHandler)
                .failureHandler(oauth2AuthenticationFailureHandler())
            );
        }

        return http.build();
    }

    private AuthenticationEntryPoint problemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> writeProblem(
            response,
            HttpStatus.UNAUTHORIZED,
            "Authentication is required",
            objectMapper
        );
    }

    private AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> writeProblem(
            response,
            HttpStatus.FORBIDDEN,
            "Access is denied",
            objectMapper
        );
    }

    private AuthenticationFailureHandler oauth2AuthenticationFailureHandler() {
        SimpleUrlAuthenticationFailureHandler delegate = new SimpleUrlAuthenticationFailureHandler(OAUTH2_FAILURE_REDIRECT_URL);
        return (request, response, exception) -> {
            OAuth2Error oauth2Error = exception instanceof OAuth2AuthenticationException oauth2Exception
                ? oauth2Exception.getError()
                : null;
            if (oauth2Error != null) {
                log.warn(
                    "oauth2 authentication failed: registrationId={}, errorCode={}, errorDescription={}, responseErrorCode={}, responseErrorDescription={}, exception={}",
                    callbackRegistrationId(request),
                    sanitizeLogValue(oauth2Error.getErrorCode()),
                    sanitizeLogValue(oauth2Error.getDescription()),
                    sanitizeLogValue(request.getParameter("error")),
                    sanitizeLogValue(request.getParameter("error_description")),
                    exception.getClass().getSimpleName(),
                    exception
                );
            } else {
                log.warn(
                    "oauth2 authentication failed: registrationId={}, responseErrorCode={}, responseErrorDescription={}, exception={}, message={}",
                    callbackRegistrationId(request),
                    sanitizeLogValue(request.getParameter("error")),
                    sanitizeLogValue(request.getParameter("error_description")),
                    exception.getClass().getSimpleName(),
                    sanitizeLogValue(exception.getMessage()),
                    exception
                );
            }
            delegate.onAuthenticationFailure(request, response, exception);
        };
    }

    private String callbackRegistrationId(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/login/oauth2/code/";
        String requestUri = request.getRequestURI();
        int start = requestUri.indexOf(prefix);
        if (start < 0) {
            return "unknown";
        }
        return sanitizeLogValue(requestUri.substring(start + prefix.length()));
    }

    private String sanitizeLogValue(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        String sanitized = value.replaceAll("[\\r\\n\\t]+", " ");
        if (sanitized.length() <= MAX_LOG_VALUE_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_LOG_VALUE_LENGTH) + "...";
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail, ObjectMapper objectMapper) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}
