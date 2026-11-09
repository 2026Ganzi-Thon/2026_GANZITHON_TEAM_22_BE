package kr.ganzithon.backend.identity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final IdentityService service;
    public IdentityController(IdentityService service) { this.service=service; }
    @PostMapping("/api/v1/guest-sessions") @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.GuestDTO create(HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); return service.create();
    }
    @GetMapping("/api/v1/me")
    public IdentityService.MeDTO me(@RequestAttribute("identity") IdentityService.MeDTO identity, HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); return identity;
    }
    @DeleteMapping("/api/v1/me")
    public ResponseEntity<Void> delete(@RequestAttribute("userId") UUID userId,
                                       @RequestBody(required = false) JsonNode body) {
        if (body != null) {
            throw new ApiException(400, "VALIDATION_FAILED", "전체 삭제 요청에는 본문을 보내지 않습니다.");
        }
        service.deleteCurrentUser(userId);
        return ResponseEntity.noContent().build();
    }
}
