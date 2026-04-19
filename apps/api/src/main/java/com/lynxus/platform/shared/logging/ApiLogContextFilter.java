package com.lynxus.platform.shared.logging;

import com.lynxus.contracts.runtime.LogContextHeaders;
import com.lynxus.contracts.runtime.TraceContext;
import com.lynxus.platform.auth.CurrentUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiLogContextFilter extends OncePerRequestFilter {
    private static final Pattern SESSION_PATH = Pattern.compile("/api/session-runtime/sessions/([^/]+)");
    private final CurrentUserResolver currentUserResolver;
    private final ObjectMapper objectMapper;

    public ApiLogContextFilter(CurrentUserResolver currentUserResolver, ObjectMapper objectMapper) {
        this.currentUserResolver = currentUserResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        HttpServletRequest requestToUse = request;
        if (shouldCacheBody(request)) {
            requestToUse = new CachedBodyHttpServletRequest(request);
        }
        TraceContext traceContext = TraceContext.fromTraceparent(request.getHeader(LogContextHeaders.TRACEPARENT));
        String sessionId = extractPath(SESSION_PATH, requestToUse.getRequestURI());
        String customerId = extractCustomerId(requestToUse);
        String userId = resolveUserId();
        response.setHeader(LogContextHeaders.TRACEPARENT, traceContext.toTraceparent());
        try (PlatformLogContext.Scope ignored = PlatformLogContext.open(traceContext, sessionId, null, customerId, userId)) {
            filterChain.doFilter(requestToUse, response);
        }
    }

    private boolean shouldCacheBody(HttpServletRequest request) {
        String method = request.getMethod();
        String contentType = request.getContentType();
        return ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))
            && contentType != null
            && contentType.startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    private String extractCustomerId(HttpServletRequest request) throws IOException {
        if (!(request instanceof CachedBodyHttpServletRequest cachedRequest) || cachedRequest.cachedBody().length == 0) {
            return null;
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(cachedRequest.cachedBody());
            return value(jsonNode, "customerId");
        } catch (JacksonException ignored) {
            return null;
        }
    }

    private String resolveUserId() {
        try {
            return currentUserResolver.resolveCurrentUser().id();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String extractPath(Pattern pattern, String uri) {
        Matcher matcher = pattern.matcher(uri);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String value(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
