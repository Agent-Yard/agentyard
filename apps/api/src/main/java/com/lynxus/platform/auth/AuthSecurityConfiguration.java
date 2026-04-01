package com.lynxus.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class AuthSecurityConfiguration {
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider,
        OidcProvisioningSuccessHandler oidcProvisioningSuccessHandler,
        SecurityContextRepository securityContextRepository
    ) throws Exception {
        Supplier<ObjectMapper> objectMapperSupplier = ObjectMapper::new;
        http
            .csrf(AbstractHttpConfigurer::disable)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(
                    "/api/system/health",
                    "/api/auth/login",
                    "/api/auth/dev-bootstrap-login",
                    "/oauth2/authorization/**",
                    "/login/oauth2/code/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .defaultAuthenticationEntryPointFor(
                    problemAuthenticationEntryPoint(objectMapperSupplier),
                    request -> request.getRequestURI().startsWith("/api/")
                )
                .defaultAccessDeniedHandlerFor(
                    problemAccessDeniedHandler(objectMapperSupplier),
                    request -> request.getRequestURI().startsWith("/api/")
                )
            );

        if (clientRegistrationRepositoryProvider.getIfAvailable() != null) {
            http.oauth2Login(oauth2 -> oauth2.successHandler(oidcProvisioningSuccessHandler));
        }

        return http.build();
    }

    private AuthenticationEntryPoint problemAuthenticationEntryPoint(Supplier<ObjectMapper> objectMapperSupplier) {
        return (request, response, authException) -> writeProblem(
            response,
            HttpStatus.UNAUTHORIZED,
            "Authentication is required",
            objectMapperSupplier.get()
        );
    }

    private AccessDeniedHandler problemAccessDeniedHandler(Supplier<ObjectMapper> objectMapperSupplier) {
        return (request, response, accessDeniedException) -> writeProblem(
            response,
            HttpStatus.FORBIDDEN,
            "Access is denied",
            objectMapperSupplier.get()
        );
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail, ObjectMapper objectMapper) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}
