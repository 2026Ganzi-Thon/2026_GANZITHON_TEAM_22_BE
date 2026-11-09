package kr.ganzithon.backend.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestRateLimitFilter extends OncePerRequestFilter {
    private final RequestRateLimiter limiter;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public RequestRateLimitFilter(RequestRateLimiter limiter, ObjectMapper mapper,
                                  @Value("${ganzi.rate-limit.enabled}") boolean enabled) {
        this.limiter = limiter;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/") || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String requestId = UUID.randomUUID().toString();
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("Cache-Control", "no-store");
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }

        RequestRateLimiter.Category category = category(path, request.getMethod());
        RequestRateLimiter.Decision decision = limiter.allow(request.getRemoteAddr(), category);
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(),
                new ApiError("RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
                        requestId, true));
    }

    private static RequestRateLimiter.Category category(String path, String method) {
        if (!"POST".equals(method)) return RequestRateLimiter.Category.GENERAL;
        if ("/api/v1/guest-sessions".equals(path)) return RequestRateLimiter.Category.GUEST;
        if ("/api/v1/auth/register".equals(path)
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/auth/password".equals(path)) return RequestRateLimiter.Category.AUTH;
        return RequestRateLimiter.Category.GENERAL;
    }
}
