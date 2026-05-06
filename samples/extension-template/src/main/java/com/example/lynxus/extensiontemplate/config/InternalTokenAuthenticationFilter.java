package com.example.lynxus.extensiontemplate.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lynxus.extension.sdk.generated.protocol.model.ExtensionError;
import com.lynxus.extension.sdk.protocol.LynxusExtensionHeaders;
import com.lynxus.extension.sdk.protocol.LynxusExtensionProtocol;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class InternalTokenAuthenticationFilter extends OncePerRequestFilter {
    private final ExtensionTemplateProperties properties;
    private final ObjectMapper objectMapper;

    public InternalTokenAuthenticationFilter(ExtensionTemplateProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return LynxusExtensionProtocol.HEALTH_LIVE_PATH.equals(path)
            || LynxusExtensionProtocol.HEALTH_READY_PATH.equals(path)
            || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(LynxusExtensionHeaders.AUTHORIZATION);
        if (!("Bearer " + properties.internalAuthToken()).equals(authorization)) {
            writeAuthFailure(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeAuthFailure(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
            response.getOutputStream(),
            new ExtensionError()
                .errorCode("AUTH_FAILED")
                .message("missing or invalid Lynxus internal bearer token")
                .category(ExtensionError.CategoryEnum.AUTH)
                .retryable(false)
                .details(Map.of())
        );
    }
}
