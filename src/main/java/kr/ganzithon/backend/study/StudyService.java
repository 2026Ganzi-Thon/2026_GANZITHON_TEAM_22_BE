package kr.ganzithon.backend.study;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import kr.ganzithon.backend.notification.StudyAwayService;
import kr.ganzithon.backend.reminder.ReminderService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyService {
    private final PortableJdbc jdbc;
    private final Clock clock;
    private final ReminderService reminders;
    private final StudyAwayService away;

    public StudyService(JdbcTemplate jdbc, Clock clock, ReminderService reminders, StudyAwayService away) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
        this.reminders = reminders;
        this.away = away;
    }

    public record StartResult(StudySessionDto session, boolean created) { }
    public record CurrentResult(StudySessionDto studySession, OffsetDateTime serverTime) { }
    public record SuggestedTask(UUID taskId, String title, long completedStudySeconds) { }
    public record StartContext(StudySessionDto currentSession, SuggestedTask suggestedTask,
                               OffsetDateTime serverTime) { }

    private record Session(UUID id, UUID taskId, UUID startRequestId, String startRequestHash,
                           String status, int version, long accumulatedMs,
                           OffsetDateTime runningStartedAt, OffsetDateTime startedAt,
                           OffsetDateTime endedAt, String endReason) { }

    private static final RowMapper<Session> SESSION_MAPPER = (rs, rowNum) -> new Session(
            PortableJdbc.uuid(rs, "id"), PortableJdbc.uuid(rs, "task_id"),
            PortableJdbc.uuid(rs, "start_request_id"), rs.getString("start_request_hash"),
            rs.getString("status"), rs.getInt("version"), rs.getLong("accumulated_ms"),
            PortableJdbc.utc(rs, "running_started_at"),
            PortableJdbc.utc(rs, "started_at"),
            PortableJdbc.utc(rs, "ended_at"), rs.getString("end_reason"));

    private static final String SELECT_COLUMNS = "SELECT id, task_id, start_request_id, start_request_hash, "
            + "status, version, accumulated_ms, running_started_at, started_at, ended_at, end_reason "
            + "FROM study_sessions ";

    @Transactional
    public StartResult start(UUID userId, UUID clientRequestId, UUID taskId) {
        lockUser(userId);
        String fingerprint = fingerprint(taskId);
        List<Session> old = jdbc.query(SELECT_COLUMNS + "WHERE user_id = ? AND start_request_id = ?",
                SESSION_MAPPER, userId, clientRequestId);
        if (!old.isEmpty()) {
            Session saved = old.get(0);
            if (!fingerprint.equals(saved.startRequestHash())) {
                throw new ApiException(409, "CLIENT_REQUEST_ID_REUSED", "This request ID was used with different input");
            }
            return new StartResult(toDto(saved, now()), false);
        }

        List<UUID> active = jdbc.queryForList(
                "SELECT id FROM study_sessions WHERE user_id = ? AND status IN ('STUDYING', 'PAUSED')",
                UUID.class, userId);
        if (!active.isEmpty()) {
            throw new ApiException(409, "ACTIVE_STUDY_EXISTS", "End the current study session first");
        }
        if (taskId != null) {
            List<String> statuses = jdbc.queryForList(
                    "SELECT status FROM tasks WHERE id = ? AND user_id = ?", String.class, taskId, userId);
            if (statuses.isEmpty()) {
                throw new ApiException(404, "RESOURCE_NOT_FOUND", "Task not found");
            }
            if (!"TODO".equals(statuses.get(0))) {
                throw new ApiException(409, "TASK_COMPLETED", "Only TODO tasks can start a study session");
            }
        }
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        jdbc.update("INSERT INTO study_sessions (id, user_id, task_id, start_request_id, start_request_hash, "
                        + "status, version, accumulated_ms, running_started_at, started_at, ended_at, end_reason, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'STUDYING', 0, 0, ?, ?, NULL, NULL, ?, ?)",
                id, userId, taskId, clientRequestId, fingerprint, now, now, now, now);
        reminders.cancelOnStudyStart(userId, now);
        return new StartResult(toDto(find(userId, id), now), true);
    }

    @Transactional(readOnly = true)
    public CurrentResult current(UUID userId) {
        OffsetDateTime now = now();
        return new CurrentResult(currentSession(userId, now), now);
    }

    @Transactional(readOnly = true)
    public StartContext startContext(UUID userId) {
        OffsetDateTime now = now();
        StudySessionDto current = currentSession(userId, now);
        if (current != null) {
            return new StartContext(current, null, now);
        }

        var suggestions = jdbc.query("""
                SELECT t.id, t.title, SUM(s.accumulated_ms) AS completed_ms
                FROM tasks t JOIN study_sessions s ON s.task_id = t.id AND s.user_id = t.user_id
                WHERE t.user_id = ? AND t.status = 'TODO' AND s.status = 'ENDED'
                GROUP BY t.id, t.title
                ORDER BY MAX(s.ended_at) DESC, MAX(s.started_at) DESC, t.id DESC
                LIMIT 1
                """, (rs, row) -> new SuggestedTask(PortableJdbc.uuid(rs, "id"),
                rs.getString("title"), rs.getLong("completed_ms") / 1000L), userId);
        return new StartContext(null, suggestions.isEmpty() ? null : suggestions.getFirst(), now);
    }

    private StudySessionDto currentSession(UUID userId, OffsetDateTime now) {
        List<Session> sessions = jdbc.query(SELECT_COLUMNS
                        + "WHERE user_id = ? AND status IN ('STUDYING', 'PAUSED')",
                SESSION_MAPPER, userId);
        return sessions.isEmpty() ? null : toDto(sessions.getFirst(), now);
    }

    @Transactional(readOnly = true)
    public StudySessionDto one(UUID userId, UUID sessionId) {
        return toDto(find(userId, sessionId), now());
    }

    @Transactional
    public StudySessionDto transition(UUID userId, UUID sessionId, String action,
                                      int expectedVersion, String endReason) {
        lockUser(userId);
        Session current = find(userId, sessionId);
        OffsetDateTime now = now();
        if ("ENDED".equals(current.status())) {
            if ("END".equals(action)) {
                return toDto(current, now);
            }
            throw new ApiException(409, "INVALID_STUDY_STATE", "Ended study cannot be paused or resumed");
        }
        if (("PAUSE".equals(action) && "PAUSED".equals(current.status()))
                || ("RESUME".equals(action) && "STUDYING".equals(current.status()))) {
            return toDto(current, now);
        }
        if (expectedVersion != current.version()) {
            throw new ApiException(409, "STUDY_VERSION_CONFLICT", "Study session version changed");
        }
        switch (action) {
            case "PAUSE" -> jdbc.update("UPDATE study_sessions SET status = 'PAUSED', "
                            + "accumulated_ms = ?, running_started_at = NULL, version = version + 1, updated_at = ? "
                            + "WHERE id = ? AND user_id = ?",
                    elapsedTotal(current, now), now, sessionId, userId);
            case "RESUME" -> jdbc.update("UPDATE study_sessions SET status = 'STUDYING', "
                            + "running_started_at = ?, version = version + 1, updated_at = ? "
                            + "WHERE id = ? AND user_id = ?",
                    now, now, sessionId, userId);
            case "END" -> jdbc.update("UPDATE study_sessions SET status = 'ENDED', "
                            + "accumulated_ms = ?, running_started_at = NULL, ended_at = ?, end_reason = ?, "
                            + "version = version + 1, updated_at = ? WHERE id = ? AND user_id = ?",
                    elapsedTotal(current, now), now, endReason, now, sessionId, userId);
            default -> throw new ApiException(400, "VALIDATION_FAILED", "Invalid study action");
        }
        if ("PAUSE".equals(action) || "END".equals(action)) {
            away.cancelOnStudyTransition(userId, sessionId, now);
        }
        return toDto(find(userId, sessionId), now);
    }

    private void lockUser(UUID userId) {
        List<UUID> users = jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
        if (users.isEmpty()) {
            throw new ApiException(401, "AUTH_REQUIRED", "Unknown user");
        }
    }

    private Session find(UUID userId, UUID id) {
        List<Session> rows = jdbc.query(SELECT_COLUMNS + "WHERE user_id = ? AND id = ?", SESSION_MAPPER, userId, id);
        if (rows.isEmpty()) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "Study session not found");
        }
        return rows.get(0);
    }

    private StudySessionDto toDto(Session session, OffsetDateTime serverTime) {
        return new StudySessionDto(session.id(), session.taskId(), session.status(), session.version(),
                session.startedAt(), session.endedAt(), session.endReason(),
                elapsedTotal(session, serverTime) / 1000L, session.runningStartedAt(),
                session.startRequestId(), serverTime);
    }

    private long elapsedTotal(Session session, OffsetDateTime now) {
        if (session.runningStartedAt() == null) {
            return session.accumulatedMs();
        }
        return session.accumulatedMs()
                + Math.max(0L, Duration.between(session.runningStartedAt(), now).toMillis());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String fingerprint(UUID taskId) {
        String canonical = "study-start/v0.3\n" + (taskId == null ? "null" : taskId.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
