package com.lynxus.channel.gateway.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class InternalAuthSecurityConfiguration {
    @Bean
    String internalAuthToken(@Value("${lynxus.internal-auth.token:}") String internalAuthToken) {
        if (internalAuthToken == null || internalAuthToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        return internalAuthToken.trim();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        @Qualifier("internalAuthToken") String internalAuthToken,
        ObjectMapper objectMapper
    ) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new InternalTokenAuthenticationFilter(internalAuthToken), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/connectors/**").permitAll()
                .requestMatchers(
                    "/extension/manifest",
                    "/extension/channel/outbound-frame-subscriptions",
                    "/extension/channel/outbound-frames/**"
                ).authenticated()
                .requestMatchers("/internal/**").authenticated()
                .anyRequest().denyAll()
            )
            .exceptionHandling(exceptionHandling -> exceptionHandling
                .authenticationEntryPoint((request, response, authException) -> writeProblem(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "Authentication is required",
                    objectMapper
                ))
                .accessDeniedHandler((request, response, accessDeniedException) -> writeProblem(
                    response,
                    HttpStatus.FORBIDDEN,
                    "Access is denied",
                    objectMapper
                ))
            );
        return http.build();
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String detail, ObjectMapper objectMapper) throws IOException {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(status.getReasonPhrase());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problemDetail);
    }
}
