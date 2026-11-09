package kr.ganzithon.backend.reminder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReminderService {
    private final PortableJdbc jdbc;
    private final Clock clock;

    public ReminderService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
    }

    public record PutResult(ReminderDto.StartReminder reminder, OffsetDateTime serverTime, boolean created) { }

    private record Source(UUID id, String status, OffsetDateTime startedAt) { }

    private static final RowMapper<ReminderDto.StartReminder> REMINDER_MAPPER = (rs, row) ->
            new ReminderDto.StartReminder(PortableJdbc.uuid(rs, "id"),
                    PortableJdbc.uuid(rs, "source_session_id"),
                    PortableJdbc.utc(rs, "remind_at"),
                    rs.getString("time_zone"), rs.getString("status"),
                    rs.getString("canceled_reason"),
                    PortableJdbc.utc(rs, "created_at"),
                    PortableJdbc.utc(rs, "updated_at"));
    private static final String REMINDER_COLUMNS = "SELECT id,source_session_id,remind_at,time_zone,"
            + "status,canceled_reason,created_at,updated_at FROM start_reminders ";

    @Transactional
    public PutResult put(UUID userId, UUID sourceSessionId, OffsetDateTime remindAt, String timeZone) {
        requireIanaZone(timeZone);
        if (remindAt == null) {
            throw badRequest("remindAt is required");
        }
        OffsetDateTime normalized = remindAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        lockUser(userId);
        OffsetDateTime now = now();
        expire(userId, now);
        List<ReminderDto.StartReminder> old = jdbc.query(REMINDER_COLUMNS
                + "WHERE user_id = ? AND source_session_id = ?", REMINDER_MAPPER, userId, sourceSessionId);
        if (!old.isEmpty()) {
            ReminderDto.StartReminder saved = old.get(0);
            if (saved.remindAt().isEqual(normalized) && saved.timeZone().equals(timeZone)) {
                return new PutResult(saved, now, false);
            }
            throw new ApiException(409, "REMINDER_REQUEST_CONFLICT",
                    "Reminder for this study session already has different input");
        }
        if (!normalized.isAfter(now) || normalized.isAfter(now.plusDays(7))) {
            throw badRequest("remindAt must be in the future and within seven days");
        }
        List<UUID> ownedSource = jdbc.queryForList(
                "SELECT id FROM study_sessions WHERE id = ? AND user_id = ?",
                UUID.class, sourceSessionId, userId);
        if (ownedSource.isEmpty()) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "Study session not found");
        }
        List<Source> sources = jdbc.query("SELECT id,status,started_at FROM study_sessions "
                        + "WHERE user_id = ? ORDER BY started_at DESC,id DESC LIMIT 2",
                (rs, row) -> new Source(PortableJdbc.uuid(rs, "id"), rs.getString("status"),
                        PortableJdbc.utc(rs, "started_at")), userId);
        if (sources.size() > 1 && sources.get(0).startedAt().isEqual(sources.get(1).startedAt())) {
            throw new ApiException(409, "REMINDER_SOURCE_AMBIGUOUS",
                    "Latest study session cannot be determined from identical start times");
        }
        if (sources.isEmpty() || !sources.get(0).id().equals(sourceSessionId)) {
            throw new ApiException(409, "REMINDER_SOURCE_NOT_LATEST", "The source must be the latest study session");
        }
        if (!"ENDED".equals(sources.get(0).status())) {
            throw new ApiException(409, "STUDY_NOT_ENDED", "End the study session first");
        }
        List<UUID> active = jdbc.queryForList("SELECT id FROM start_reminders WHERE user_id = ? AND status = 'ACTIVE'",
                UUID.class, userId);
        if (!active.isEmpty()) {
            throw new ApiException(409, "ACTIVE_REMINDER_EXISTS", "Cancel the current reminder first");
        }
        UUID reminderId = UUID.randomUUID();
        jdbc.update("INSERT INTO start_reminders (id,user_id,source_session_id,remind_at,time_zone,status,"
                        + "canceled_reason,created_at,updated_at) VALUES (?,?,?,?,?,'ACTIVE',NULL,?,?)",
                reminderId, userId, sourceSessionId, normalized, timeZone, now, now);
        return new PutResult(find(userId, reminderId), now, true);
    }

    @Transactional
    public ReminderDto.Current current(UUID userId) {
        OffsetDateTime now = now();
        lockUser(userId);
        expire(userId, now);
        List<ReminderDto.StartReminder> rows = jdbc.query(REMINDER_COLUMNS
                + "WHERE user_id = ? AND status = 'ACTIVE'", REMINDER_MAPPER, userId);
        return new ReminderDto.Current(rows.isEmpty() ? null : rows.get(0), now);
    }

    @Transactional
    public ReminderDto.Current changeTime(UUID userId, UUID reminderId,
            OffsetDateTime expectedRemindAt, String expectedTimeZone,
            OffsetDateTime remindAt, String timeZone) {
        requireIanaZone(expectedTimeZone);
        requireIanaZone(timeZone);
        if (expectedRemindAt == null || remindAt == null) {
            throw badRequest("expectedRemindAt and remindAt are required");
        }
        OffsetDateTime expected = expectedRemindAt.withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime target = remindAt.withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        lockUser(userId);
        OffsetDateTime now = now();
        expire(userId, now);
        ReminderDto.StartReminder saved = find(userId, reminderId);
        if (!"ACTIVE".equals(saved.status())) {
            throw new ApiException(409, "REMINDER_NOT_ACTIVE", "Only an active reminder can be changed");
        }
        if (!target.isAfter(now) || target.isAfter(now.plusDays(7))) {
            throw badRequest("remindAt must be in the future and within seven days");
        }
        if (saved.remindAt().isEqual(target) && saved.timeZone().equals(timeZone)) {
            return new ReminderDto.Current(saved, now);
        }
        if (!saved.remindAt().isEqual(expected) || !saved.timeZone().equals(expectedTimeZone)) {
            throw new ApiException(409, "REMINDER_PRECONDITION_FAILED",
                    "Reminder changed since it was observed");
        }
        int changed = jdbc.update("UPDATE start_reminders SET remind_at = ?, time_zone = ?, updated_at = ? "
                        + "WHERE id = ? AND user_id = ? AND status = 'ACTIVE' AND remind_at = ? AND time_zone = ?",
                target, timeZone, now, reminderId, userId, expected, expectedTimeZone);
        if (changed != 1) {
            throw new ApiException(409, "REMINDER_PRECONDITION_FAILED",
                    "Reminder changed since it was observed");
        }
        return new ReminderDto.Current(find(userId, reminderId), now);
    }

    @Transactional
    public void cancel(UUID userId, UUID reminderId) {
        lockUser(userId);
        OffsetDateTime now = now();
        expire(userId, now);
        find(userId, reminderId);
        jdbc.update("UPDATE start_reminders SET status = 'CANCELED', canceled_reason = 'USER_CANCELED', "
                + "updated_at = ? WHERE user_id = ? AND id = ? AND status = 'ACTIVE'", now, userId, reminderId);
    }

    /** Called after a genuinely new study session is inserted, within the same transaction and user lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOnStudyStart(UUID userId, OffsetDateTime now) {
        expire(userId, now);
        jdbc.update("UPDATE start_reminders SET status = 'CANCELED', canceled_reason = 'NEW_STUDY', "
                + "updated_at = ? WHERE user_id = ? AND status = 'ACTIVE'", now, userId);
    }

    @Transactional(readOnly = true)
    public ReminderDto.TaskCandidates taskCandidates(UUID userId, String timeZone, int limit, int offset) {
        ZoneId zone = requireIanaZone(timeZone);
        if (limit < 1 || limit > 100 || offset < 0 || offset > Integer.MAX_VALUE - limit) {
            throw badRequest("limit must be 1..100 and offset must be nonnegative");
        }
        OffsetDateTime now = now();
        LocalDate earliestDue = LocalDate.ofInstant(now.toInstant(), zone).plusDays(1);
        List<ReminderDto.TaskCandidate> fetched = jdbc.query(
                "SELECT id,due_date FROM tasks WHERE user_id = ? AND status = 'TODO' AND due_date >= ? "
                        + "ORDER BY due_date,id LIMIT ? OFFSET ?",
                (rs, row) -> {
                    LocalDate dueDate = rs.getObject("due_date", LocalDate.class);
                    return new ReminderDto.TaskCandidate(PortableJdbc.uuid(rs, "id"), dueDate,
                            dueDate.minus(1, ChronoUnit.DAYS));
                }, userId, earliestDue, limit + 1, offset);
        boolean more = fetched.size() > limit;
        List<ReminderDto.TaskCandidate> items = new ArrayList<>(fetched.subList(0, Math.min(limit, fetched.size())));
        return new ReminderDto.TaskCandidates(items, more ? offset + limit : null, now);
    }

    private ReminderDto.StartReminder find(UUID userId, UUID id) {
        List<ReminderDto.StartReminder> rows = jdbc.query(REMINDER_COLUMNS + "WHERE user_id = ? AND id = ?",
                REMINDER_MAPPER, userId, id);
        if (rows.isEmpty()) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "Reminder not found");
        }
        return rows.get(0);
    }

    private void lockUser(UUID userId) {
        List<UUID> rows = jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
        if (rows.isEmpty()) {
            throw new ApiException(404, "RESOURCE_NOT_FOUND", "User not found");
        }
    }

    private void expire(UUID userId, OffsetDateTime now) {
        jdbc.update("UPDATE start_reminders SET status = 'EXPIRED', updated_at = ? "
                + "WHERE user_id = ? AND status = 'ACTIVE' AND remind_at <= ?", now, userId, now);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC); }

    private static ZoneId requireIanaZone(String value) {
        if (value == null || !ZoneId.getAvailableZoneIds().contains(value)) {
            throw badRequest("timeZone must be an IANA time zone");
        }
        return ZoneId.of(value);
    }

    private static ApiException badRequest(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }
}
