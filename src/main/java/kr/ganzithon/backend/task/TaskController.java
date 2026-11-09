package kr.ganzithon.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TaskDto> create(@RequestAttribute("userId") UUID userId,
                                          @RequestBody JsonNode body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(userId, body));
    }

    @GetMapping
    public TaskDto.TaskList list(@RequestAttribute("userId") UUID userId,
                                 @RequestParam(required = false) String status) {
        return service.list(userId, status);
    }

    @GetMapping("/{taskId}")
    public TaskDto.Detail get(@RequestAttribute("userId") UUID userId,
                              @PathVariable UUID taskId) {
        return service.get(userId, taskId);
    }

    @PatchMapping("/{taskId}")
    public TaskDto patch(@RequestAttribute("userId") UUID userId,
                         @PathVariable UUID taskId,
                         @RequestBody JsonNode body) {
        return service.patch(userId, taskId, body);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@RequestAttribute("userId") UUID userId,
                                       @PathVariable UUID taskId) {
        service.delete(userId, taskId);
        return ResponseEntity.noContent().build();
    }
}
