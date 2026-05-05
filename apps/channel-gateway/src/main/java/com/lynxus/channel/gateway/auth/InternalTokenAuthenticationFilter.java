package com.lynxus.channel.gateway.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class InternalTokenAuthenticationFilter extends OncePerRequestFilter {
    private final String expectedToken;

    public InternalTokenAuthenticationFilter(String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalStateException("lynxus.internal-auth.token must be configured");
        }
        this.expectedToken = expectedToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/internal/")
            && !path.equals("/extension/manifest")
            && !path.equals("/extension/channel/outbound-frame-subscriptions")
            && !path.startsWith("/extension/channel/outbound-frames/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String actualToken = authorization.substring("Bearer ".length()).trim();
            if (expectedToken.equals(actualToken)) {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        "internal-service",
                        "N/A",
                        AuthorityUtils.createAuthorityList("ROLE_INTERNAL")
                    )
                );
            }
        }
        filterChain.doFilter(request, response);
    }
}
