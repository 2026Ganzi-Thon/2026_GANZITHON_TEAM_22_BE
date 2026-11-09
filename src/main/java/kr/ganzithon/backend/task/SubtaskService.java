package kr.ganzithon.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubtaskService {
    private static final Set<String> CREATE_FIELDS = Set.of("title");
    private static final Set<String> PATCH_FIELDS = Set.of("title", "status");
    private final PortableJdbc jdbc;
    private final Clock clock;
    private final TaskService tasks;

    public SubtaskService(JdbcTemplate jdbc, Clock clock, TaskService tasks) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
        this.tasks = tasks;
    }

    @Transactional
    public SubtaskDto create(UUID userId, UUID taskId, JsonNode body) {
        TaskService.checkFields(body, CREATE_FIELDS, false);
        String title = TaskService.title(body.get("title"));
        tasks.lockUser(userId);
        tasks.findOwned(userId, taskId);
        Integer position = jdbc.queryForObject("""
                SELECT COALESCE(MAX(position), 0) + 1 FROM subtasks WHERE task_id = ?
                """, Integer.class, taskId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.update("""
                INSERT INTO subtasks (id, task_id, title, status, position,
                                      completed_at, created_at, updated_at)
                VALUES (?, ?, ?, 'TODO', ?, NULL, ?, ?)
                """, id, taskId, title, position, now, now);
        return findOwned(taskId, id);
    }

    @Transactional
    public SubtaskDto patch(UUID userId, UUID taskId, UUID subtaskId, JsonNode body) {
        TaskService.checkFields(body, PATCH_FIELDS, true);
        String newTitle = body.has("title") ? TaskService.title(body.get("title")) : null;
        String newStatus = body.has("status") ? TaskService.status(body.get("status")) : null;
        tasks.lockUser(userId);
        tasks.findOwned(userId, taskId);
        SubtaskDto current = findOwned(taskId, subtaskId);
        String title = body.has("title") ? newTitle : current.title();
        String status = body.has("status") ? newStatus : current.status();
        OffsetDateTime completedAt = current.completedAt() == null ? null
                : OffsetDateTime.ofInstant(current.completedAt(), ZoneOffset.UTC);
        if (!status.equals(current.status())) {
            completedAt = "DONE".equals(status) ? now() : null;
        }
        jdbc.update("""
                UPDATE subtasks SET title = ?, status = ?, completed_at = ?, updated_at = ?
                WHERE id = ? AND task_id = ?
                """, title, status, completedAt, now(), subtaskId, taskId);
        return findOwned(taskId, subtaskId);
    }

    @Transactional
    public void delete(UUID userId, UUID taskId, UUID subtaskId) {
        tasks.lockUser(userId);
        tasks.findOwned(userId, taskId);
        findOwned(taskId, subtaskId);
        jdbc.update("DELETE FROM subtasks WHERE id = ? AND task_id = ?", subtaskId, taskId);
    }

    private SubtaskDto findOwned(UUID taskId, UUID subtaskId) {
        List<SubtaskDto> rows = jdbc.query("""
                SELECT * FROM subtasks WHERE id = ? AND task_id = ?
                """, SubtaskService::mapSubtask, subtaskId, taskId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "Subtask not found");
        }
        return rows.get(0);
    }

    static SubtaskDto mapSubtask(ResultSet rs, int rowNum) throws SQLException {
        return new SubtaskDto(
                PortableJdbc.uuid(rs, "id"), PortableJdbc.uuid(rs, "task_id"),
                rs.getString("title"), rs.getString("status"), rs.getInt("position"),
                TaskService.instant(rs, "completed_at"), TaskService.instant(rs, "created_at"),
                TaskService.instant(rs, "updated_at"));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
