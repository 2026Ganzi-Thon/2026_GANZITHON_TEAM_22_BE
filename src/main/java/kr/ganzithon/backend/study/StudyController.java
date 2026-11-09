package kr.ganzithon.backend.study;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/study-sessions")
public class StudyController {
    private final StudyService service;

    public StudyController(StudyService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StudySessionDto> start(@RequestAttribute("userId") UUID userId,
                                                  @RequestBody JsonNode body) {
        requireFields(body, Set.of("clientRequestId", "taskId"));
        UUID requestId = uuid(body.get("clientRequestId"), "clientRequestId", false);
        UUID taskId = uuid(body.get("taskId"), "taskId", true);
        StudyService.StartResult result = service.start(userId, requestId, taskId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.session());
    }

    @GetMapping("/current")
    public StudyService.CurrentResult current(@RequestAttribute("userId") UUID userId) {
        return service.current(userId);
    }

    @GetMapping("/start-context")
    public StudyService.StartContext startContext(@RequestAttribute("userId") UUID userId) {
        return service.startContext(userId);
    }

    @GetMapping("/{sessionId}")
    public StudySessionDto one(@RequestAttribute("userId") UUID userId, @PathVariable UUID sessionId) {
        return service.one(userId, sessionId);
    }

    @PatchMapping("/{sessionId}")
    public StudySessionDto transition(@RequestAttribute("userId") UUID userId,
                                      @PathVariable UUID sessionId, @RequestBody JsonNode body) {
        requireFields(body, Set.of("action", "expectedVersion", "endReason"));
        JsonNode actionNode = body.get("action");
        if (actionNode == null || !actionNode.isTextual()) {
            throw badRequest("action must be PAUSE, RESUME or END");
        }
        String action = actionNode.textValue();
        if (!Set.of("PAUSE", "RESUME", "END").contains(action)) {
            throw badRequest("action must be PAUSE, RESUME or END");
        }
        JsonNode versionNode = body.get("expectedVersion");
        if (versionNode == null || !versionNode.isIntegralNumber()
                || !versionNode.canConvertToInt() || versionNode.intValue() < 0) {
            throw badRequest("expectedVersion must be a nonnegative integer");
        }
        String endReason = null;
        if ("END".equals(action)) {
            JsonNode reasonNode = body.get("endReason");
            if (reasonNode == null) {
                endReason = "USER_STOPPED";
            } else if (reasonNode.isTextual() && Set.of("USER_STOPPED", "USER_FINISHED").contains(reasonNode.textValue())) {
                endReason = reasonNode.textValue();
            } else {
                throw badRequest("endReason must be USER_STOPPED or USER_FINISHED");
            }
        } else if (body.has("endReason")) {
            throw badRequest("endReason is allowed only for END");
        }
        return service.transition(userId, sessionId, action, versionNode.intValue(), endReason);
    }

    private static void requireFields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) {
            throw badRequest("JSON object required");
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw badRequest("Unknown field");
            }
        });
    }

    private static UUID uuid(JsonNode node, String field, boolean nullable) {
        if (nullable && (node == null || node.isNull())) {
            return null;
        }
        if (node == null || !node.isTextual()) {
            throw badRequest(field + " must be a UUID");
        }
        try {
            return UUID.fromString(node.textValue());
        } catch (IllegalArgumentException ex) {
            throw badRequest(field + " must be a UUID");
        }
    }

    private static ApiException badRequest(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }
}
