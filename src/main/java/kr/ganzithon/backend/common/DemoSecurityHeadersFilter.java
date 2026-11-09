package kr.ganzithon.backend.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Browser demo only. AuthenticationFilter separately gates its exact static paths. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class DemoSecurityHeadersFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/demo/");
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(self), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
            + "style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        chain.doFilter(request, response);
    }
}
