package kr.ganzithon.backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StudyAwayController {
    private final StudyAwayService service;
    public StudyAwayController(StudyAwayService service) { this.service = service; }

    @PutMapping("/push-devices/{installationId}")
    public StudyAwayService.DeviceResult register(@RequestAttribute("userId") UUID userId,
                                                   @PathVariable UUID installationId,
                                                   @RequestBody JsonNode body) {
        requireFields(body, Set.of("expoPushToken"));
        return service.register(userId, installationId, text(body,"expoPushToken"));
    }

    @DeleteMapping("/push-devices/{installationId}")
    public ResponseEntity<Void> remove(@RequestAttribute("userId") UUID userId,
                                       @PathVariable UUID installationId) {
        service.remove(userId, installationId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/study-sessions/{sessionId}/presence")
    public StudyAwayService.PresenceResult presence(@RequestAttribute("userId") UUID userId,
                                                    @PathVariable UUID sessionId,
                                                    @RequestBody JsonNode body) {
        requireFields(body, Set.of("installationId","eventId","sequence","state"));
        JsonNode seq = body.get("sequence");
        if (seq == null || !seq.isIntegralNumber() || !seq.canConvertToLong() || seq.longValue() < 0) {
            throw badRequest("sequence must be a nonnegative integer");
        }
        return service.record(userId, sessionId, uuid(body,"installationId"),
                uuid(body,"eventId"), seq.longValue(), text(body,"state"));
    }

    private static void requireFields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw badRequest("JSON object required");
        body.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw badRequest("Unknown field"); });
    }
    private static String text(JsonNode body, String field) {
        JsonNode value=body.get(field);
        if (value==null || !value.isTextual()) throw badRequest(field+" must be text");
        return value.textValue();
    }
    private static UUID uuid(JsonNode body, String field) {
        try { return UUID.fromString(text(body,field)); }
        catch (IllegalArgumentException ex) { throw badRequest(field+" must be a UUID"); }
    }
    private static ApiException badRequest(String message) {
        return new ApiException(400,"VALIDATION_FAILED",message);
    }
}
