package kr.ganzithon.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private static final Set<String> CREATE_FIELDS = Set.of("title", "description", "dueDate");
    private static final Set<String> PATCH_FIELDS = Set.of("title", "description", "dueDate", "status");
    private static final String TASK_SELECT = """
            SELECT t.*,
                   (SELECT COUNT(*) FROM subtasks s WHERE s.task_id = t.id) AS subtask_total,
                   (SELECT COUNT(*) FROM subtasks s WHERE s.task_id = t.id AND s.status = 'DONE') AS subtask_done
            FROM tasks t
            """;
    private final PortableJdbc jdbc;
    private final Clock clock;

    public TaskService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
    }

    @Transactional
    public TaskDto create(UUID userId, JsonNode body) {
        checkFields(body, CREATE_FIELDS, false);
        String title = title(body.get("title"));
        String description = nullableDescription(body.get("description"));
        LocalDate dueDate = nullableDate(body.get("dueDate"));
        lockUser(userId);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.update("""
                INSERT INTO tasks (id, user_id, title, description, due_date, status,
                                   completed_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'TODO', NULL, ?, ?)
                """, id, userId, title, description, dueDate, now, now);
        return findOwned(userId, id);
    }

    @Transactional(readOnly = true)
    public TaskDto.TaskList list(UUID userId, String status) {
        if (status != null && !validStatus(status)) {
            throw badRequest("status must be TODO or DONE");
        }
        List<TaskDto> items = status == null
                ? jdbc.query(TASK_SELECT + " WHERE t.user_id = ? ORDER BY t.created_at DESC, t.id ASC",
                        TaskService::mapTask, userId)
                : jdbc.query(TASK_SELECT + " WHERE t.user_id = ? AND t.status = ? ORDER BY t.created_at DESC, t.id ASC",
                        TaskService::mapTask, userId, status);
        return new TaskDto.TaskList(items);
    }

    @Transactional(readOnly = true)
    public TaskDto.Detail get(UUID userId, UUID taskId) {
        TaskDto task = findOwned(userId, taskId);
        List<SubtaskDto> subtasks = jdbc.query("""
                SELECT * FROM subtasks WHERE task_id = ? ORDER BY position ASC, id ASC
                """, SubtaskService::mapSubtask, taskId);
        return TaskDto.Detail.of(task, subtasks);
    }

    @Transactional
    public TaskDto patch(UUID userId, UUID taskId, JsonNode body) {
        checkFields(body, PATCH_FIELDS, true);
        String newTitle = body.has("title") ? title(body.get("title")) : null;
        String newDescription = body.has("description") ? nullableDescription(body.get("description")) : null;
        LocalDate newDueDate = body.has("dueDate") ? nullableDate(body.get("dueDate")) : null;
        String newStatus = body.has("status") ? status(body.get("status")) : null;

        lockUser(userId);
        TaskDto current = findOwned(userId, taskId);
        String title = body.has("title") ? newTitle : current.title();
        String description = body.has("description") ? newDescription : current.description();
        LocalDate dueDate = body.has("dueDate") ? newDueDate : current.dueDate();
        String taskStatus = body.has("status") ? newStatus : current.status();
        OffsetDateTime completedAt = current.completedAt() == null
                ? null : OffsetDateTime.ofInstant(current.completedAt(), ZoneOffset.UTC);
        if (!taskStatus.equals(current.status())) {
            completedAt = "DONE".equals(taskStatus) ? now() : null;
        }
        jdbc.update("""
                UPDATE tasks SET title = ?, description = ?, due_date = ?, status = ?,
                                 completed_at = ?, updated_at = ?
                WHERE id = ? AND user_id = ?
                """, title, description, dueDate, taskStatus, completedAt, now(), taskId, userId);
        return findOwned(userId, taskId);
    }

    @Transactional
    public void delete(UUID userId, UUID taskId) {
        lockUser(userId);
        findOwned(userId, taskId);
        Integer activeCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM study_sessions
                WHERE task_id = ? AND user_id = ? AND status IN ('STUDYING', 'PAUSED')
                """, Integer.class, taskId, userId);
        if (activeCount != null && activeCount > 0) {
            throw new ApiException(409, "TASK_IN_USE", "Finish the study session before deleting this task");
        }
        jdbc.update("UPDATE study_sessions SET task_id = NULL WHERE task_id = ? AND user_id = ?",
                taskId, userId);
        jdbc.update("DELETE FROM tasks WHERE id = ? AND user_id = ?", taskId, userId);
    }

    void lockUser(UUID userId) {
        if (jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> PortableJdbc.uuid(rs, 1), userId).isEmpty()) {
            throw new ApiException(401, "AUTH_REQUIRED", "User not found");
        }
    }

    TaskDto findOwned(UUID userId, UUID taskId) {
        List<TaskDto> rows = jdbc.query(TASK_SELECT + " WHERE t.id = ? AND t.user_id = ?",
                TaskService::mapTask, taskId, userId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "Task not found");
        }
        return rows.get(0);
    }

    private static TaskDto mapTask(ResultSet rs, int rowNum) throws SQLException {
        int total = rs.getInt("subtask_total");
        int done = rs.getInt("subtask_done");
        return new TaskDto(
                PortableJdbc.uuid(rs, "id"), rs.getString("title"), rs.getString("description"),
                rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                rs.getString("status"), instant(rs, "completed_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"),
                new TaskDto.SubtaskSummary(total, done, total == 0 ? null : (double) done / total));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return PortableJdbc.instant(rs, column);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    static void checkFields(JsonNode body, Set<String> allowed, boolean nonempty) {
        if (body == null || !body.isObject() || (nonempty && body.isEmpty())) {
            throw badRequest("Expected a nonempty JSON object");
        }
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw badRequest("Unknown field");
            }
        });
    }

    static String title(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw badRequest("title is required and must be a string");
        }
        String value = node.textValue().strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 120) {
            throw badRequest("title must contain 1 to 120 characters");
        }
        return value;
    }

    private static String nullableDescription(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual() || node.textValue().codePointCount(0, node.textValue().length()) > 2000) {
            throw badRequest("description must be a string of at most 2000 characters or null");
        }
        return node.textValue();
    }

    private static LocalDate nullableDate(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw badRequest("dueDate must be an ISO date or null");
        try {
            return LocalDate.parse(node.textValue());
        } catch (DateTimeParseException exception) {
            throw badRequest("dueDate must be an ISO date or null");
        }
    }

    static String status(JsonNode node) {
        if (node == null || !node.isTextual() || !validStatus(node.textValue())) {
            throw badRequest("status must be TODO or DONE");
        }
        return node.textValue();
    }

    private static boolean validStatus(String value) {
        return "TODO".equals(value) || "DONE".equals(value);
    }

    static ApiException badRequest(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }
}
