package kr.ganzithon.backend.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReminderController {
    private final ReminderService service;

    public ReminderController(ReminderService service) {
        this.service = service;
    }

    @PutMapping("/study-sessions/{sessionId}/next-start-reminder")
    public ResponseEntity<ReminderDto.Current> put(@RequestAttribute("userId") UUID userId,
            @PathVariable UUID sessionId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw badRequest("JSON object required");
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of("remindAt", "timeZone").contains(name)) {
                throw badRequest("Unknown field");
            }
        });
        JsonNode remindAt = body.get("remindAt");
        JsonNode timeZone = body.get("timeZone");
        if (remindAt == null || !remindAt.isTextual() || timeZone == null || !timeZone.isTextual()) {
            throw badRequest("remindAt and timeZone must be strings");
        }
        OffsetDateTime parsed;
        try {
            parsed = OffsetDateTime.parse(remindAt.textValue());
        } catch (DateTimeParseException e) {
            throw badRequest("remindAt must be an ISO-8601 timestamp with an offset");
        }
        ReminderService.PutResult result = service.put(userId, sessionId, parsed, timeZone.textValue());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new ReminderDto.Current(result.reminder(), result.serverTime()));
    }

    @GetMapping("/start-reminders/current")
    public ReminderDto.Current current(@RequestAttribute("userId") UUID userId) {
        return service.current(userId);
    }

    @PatchMapping("/start-reminders/{reminderId}")
    public ReminderDto.Current changeTime(@RequestAttribute("userId") UUID userId,
            @PathVariable UUID reminderId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw badRequest("JSON object required");
        }
        body.fieldNames().forEachRemaining(name -> {
            if (!Set.of("expectedRemindAt", "expectedTimeZone", "remindAt", "timeZone").contains(name)) {
                throw badRequest("Unknown field");
            }
        });
        return service.changeTime(userId, reminderId,
                timestamp(body.get("expectedRemindAt"), "expectedRemindAt"),
                string(body.get("expectedTimeZone"), "expectedTimeZone"),
                timestamp(body.get("remindAt"), "remindAt"),
                string(body.get("timeZone"), "timeZone"));
    }

    @DeleteMapping("/start-reminders/{id}")
    public ResponseEntity<Void> cancel(@RequestAttribute("userId") UUID userId, @PathVariable UUID id) {
        service.cancel(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/task-reminders")
    public ReminderDto.TaskCandidates taskCandidates(@RequestAttribute("userId") UUID userId,
            @RequestParam(required = false) String timeZone,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return service.taskCandidates(userId, timeZone, limit, offset);
    }

    private static ApiException badRequest(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }

    private static String string(JsonNode node, String name) {
        if (node == null || !node.isTextual()) {
            throw badRequest(name + " must be a string");
        }
        return node.textValue();
    }

    private static OffsetDateTime timestamp(JsonNode node, String name) {
        String value = string(node, name);
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw badRequest(name + " must be an ISO-8601 timestamp with an offset");
        }
    }
}
