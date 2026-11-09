package kr.ganzithon.backend.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiError;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component @Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class AuthenticationFilter extends OncePerRequestFilter {
    private final IdentityService service;
    private final ObjectMapper mapper;
    private final boolean demoEnabled;
    public AuthenticationFilter(IdentityService service,ObjectMapper mapper,
                                @Value("${ganzi.demo.enabled:false}") boolean demoEnabled) {
        this.service=service; this.mapper=mapper; this.demoEnabled=demoEnabled;
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
        throws ServletException,IOException {
        Object existingRequestId=req.getAttribute("requestId");
        String requestId=existingRequestId==null?UUID.randomUUID().toString():existingRequestId.toString();
        req.setAttribute("requestId",requestId); res.setHeader("X-Request-ID",requestId);
        if (req.getRequestURI().startsWith("/api/v1/")) res.setHeader("Cache-Control","no-store");
        boolean demoPath=req.getRequestURI().startsWith("/demo/");
        boolean demoRead=req.getMethod().equals("GET") || req.getMethod().equals("HEAD");
        boolean publicDemoAsset=isPublicDemoAsset(req.getRequestURI());
        boolean open=req.getRequestURI().equals("/health")
            || req.getRequestURI().equals("/api/v1/guest-sessions")
            || req.getRequestURI().equals("/api/v1/auth/login")
            || req.getMethod().equals("OPTIONS")
            || demoPath && demoEnabled && demoRead && publicDemoAsset;
        try {
            if (demoPath && (!demoEnabled || !demoRead || !publicDemoAsset)) {
                throw new ApiException(404,"RESOURCE_NOT_FOUND","요청한 경로를 찾을 수 없습니다.");
            }
            boolean registration=req.getRequestURI().equals("/api/v1/auth/register");
            String header=req.getHeader("Authorization");
            if ((registration && header != null) || (!open && !registration)) {
                if(header==null || !header.startsWith("Bearer ")) throw new ApiException(401,"AUTH_REQUIRED","인증이 필요합니다.");
                String token=header.substring(7);
                var identity=service.authenticate(token);
                req.setAttribute("identity",identity); req.setAttribute("userId",identity.userId());
                req.setAttribute("accessToken",token);
            }
            chain.doFilter(req,res);
        } catch(ApiException ex) {
            res.setStatus(ex.status()); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
            mapper.writeValue(res.getOutputStream(),new ApiError(ex.code(),ex.getMessage(),requestId,false));
        }
    }

    private boolean isPublicDemoAsset(String path) {
        if (path.equals("/demo/") || path.equals("/demo/index.html")
            || path.equals("/demo/app.js") || path.equals("/demo/styles.css")) return true;
        if (!path.startsWith("/demo/assets/")) return false;
        String name=path.substring("/demo/assets/".length());
        return name.matches("[A-Za-z0-9_-][A-Za-z0-9._-]*") && !name.equals("..");
    }
}
