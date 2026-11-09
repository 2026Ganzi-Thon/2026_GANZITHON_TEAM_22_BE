package kr.ganzithon.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/subtasks")
public class SubtaskController {
    private final SubtaskService service;

    public SubtaskController(SubtaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SubtaskDto> create(@RequestAttribute("userId") UUID userId,
                                             @PathVariable UUID taskId, @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, taskId, body));
    }

    @PatchMapping("/{subtaskId}")
    public SubtaskDto patch(@RequestAttribute("userId") UUID userId,
                            @PathVariable UUID taskId, @PathVariable UUID subtaskId,
                            @RequestBody JsonNode body) {
        return service.patch(userId, taskId, subtaskId, body);
    }

    @DeleteMapping("/{subtaskId}")
    public ResponseEntity<Void> delete(@RequestAttribute("userId") UUID userId,
                                       @PathVariable UUID taskId, @PathVariable UUID subtaskId) {
        service.delete(userId, taskId, subtaskId);
        return ResponseEntity.noContent().build();
    }
}
