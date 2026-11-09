package kr.ganzithon.backend.identity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EmailAuthController {
    private final EmailAuthService service;
    public EmailAuthController(EmailAuthService service) { this.service=service; }

    public record Credentials(String email,String password) {}
    public record PasswordChange(String currentPassword,String newPassword) {}

    @PostMapping("/api/v1/auth/register") @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.AuthSessionDTO register(@RequestBody Credentials body,
                                                   HttpServletRequest request) {
        if (body == null) throw new ApiException(400,"VALIDATION_FAILED","요청 본문이 필요합니다.");
        UUID guestId=(UUID)request.getAttribute("userId");
        String token=(String)request.getAttribute("accessToken");
        return service.register(body.email(),body.password(),guestId,token);
    }

    @PostMapping("/api/v1/auth/login")
    public IdentityService.AuthSessionDTO login(@RequestBody Credentials body) {
        if (body == null) throw new ApiException(400,"VALIDATION_FAILED","요청 본문이 필요합니다.");
        return service.login(body.email(),body.password());
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(@RequestAttribute("userId") UUID userId,
                                       @RequestAttribute("accessToken") String token,
                                       @RequestBody(required=false) JsonNode body) {
        if (body != null) throw new ApiException(400,"VALIDATION_FAILED","로그아웃 요청에는 본문을 보내지 않습니다.");
        service.logout(userId,token);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/auth/password")
    public IdentityService.AuthSessionDTO password(@RequestAttribute("userId") UUID userId,
                                                   @RequestAttribute("accessToken") String token,
                                                   @RequestBody PasswordChange body) {
        if (body == null) throw new ApiException(400,"VALIDATION_FAILED","요청 본문이 필요합니다.");
        return service.changePassword(userId,token,body.currentPassword(),body.newPassword());
    }
}
