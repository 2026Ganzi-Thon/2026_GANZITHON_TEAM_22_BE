package kr.ganzithon.backend.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudyAwayService {
    private static final Pattern EXPO_TOKEN = Pattern.compile("(?:Expo|Exponent)PushToken\\[[A-Za-z0-9_-]{8,200}\\]");
    private final PortableJdbc jdbc;
    private final Clock clock;
    private final boolean deliveryEnabled;

    public StudyAwayService(JdbcTemplate jdbc, Clock clock,
                            @Value("${ganzi.push.enabled:false}") boolean deliveryEnabled) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
        this.deliveryEnabled = deliveryEnabled;
    }

    public record DeviceResult(UUID installationId, OffsetDateTime serverTime) { }
    public record PresenceResult(UUID sessionId, UUID installationId, String state,
                                 OffsetDateTime awayAt, OffsetDateTime dueAt,
                                 String deliveryStatus, boolean deliveryEnabled,
                                 OffsetDateTime serverTime) { }

    private record Presence(UUID sessionId, UUID installationId, UUID userId, UUID eventId,
                            long sequence, String state, OffsetDateTime awayAt,
                            OffsetDateTime dueAt, String deliveryStatus) { }

    @Transactional
    public DeviceResult register(UUID userId, UUID installationId, String token) {
        if (token == null || !EXPO_TOKEN.matcher(token).matches()) {
            throw invalid("expoPushToken must be an Expo push token");
        }
        lockUser(userId);
        List<UUID> owners = jdbc.queryForList(
                "SELECT user_id FROM push_devices WHERE installation_id = ?", UUID.class, installationId);
        if (!owners.isEmpty() && !owners.getFirst().equals(userId)) {
            throw new ApiException(409, "PUSH_DEVICE_OWNERSHIP_CONFLICT", "Installation belongs to another user");
        }
        String hash = hash(token);
        List<UUID> tokenOwners = jdbc.queryForList(
                "SELECT user_id FROM push_devices WHERE token_hash = ?", UUID.class, hash);
        if (!tokenOwners.isEmpty() && !tokenOwners.getFirst().equals(userId)) {
            throw new ApiException(409, "PUSH_TOKEN_OWNERSHIP_CONFLICT", "Push token belongs to another user");
        }
        List<UUID> tokenInstallations = jdbc.queryForList(
                "SELECT installation_id FROM push_devices WHERE token_hash = ?", UUID.class, hash);
        if (!tokenInstallations.isEmpty() && !tokenInstallations.getFirst().equals(installationId)) {
            // Reinstallation can change the local installation UUID without rotating the Expo token.
            // The owner row lock serializes this move with the owner's presence and study writes.
            // Deleting the old device also removes its pending presence/outbox by FK cascade.
            jdbc.update("DELETE FROM push_devices WHERE installation_id = ? AND user_id = ?",
                    tokenInstallations.getFirst(), userId);
        }
        OffsetDateTime now = now();
        try {
            if (owners.isEmpty()) {
                jdbc.update("INSERT INTO push_devices (installation_id,user_id,expo_push_token,token_hash,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?)", installationId, userId, token, hash, now, now);
            } else {
                jdbc.update("UPDATE push_devices SET expo_push_token = ?,token_hash = ?,updated_at = ? "
                        + "WHERE installation_id = ? AND user_id = ?", token, hash, now, installationId, userId);
            }
        } catch (DuplicateKeyException ex) {
            // Concurrent registration by another owner may have passed the precheck.
            throw new ApiException(409, "PUSH_DEVICE_CONFLICT", "Device or push token is already registered");
        }
        return new DeviceResult(installationId, now);
    }

    @Transactional
    public void remove(UUID userId, UUID installationId) {
        lockUser(userId);
        jdbc.update("DELETE FROM push_devices WHERE installation_id = ? AND user_id = ?", installationId, userId);
    }

    @Transactional
    public PresenceResult record(UUID userId, UUID sessionId, UUID installationId,
                                 UUID eventId, long sequence, String state) {
        if (sequence < 0 || sequence == Long.MAX_VALUE || !List.of("FOREGROUND", "BACKGROUND").contains(state)) {
            throw invalid("Invalid presence event");
        }
        lockUser(userId);
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM study_sessions WHERE id = ? AND user_id = ?", String.class, sessionId, userId);
        if (statuses.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "Study session not found");
        List<UUID> devices = jdbc.queryForList(
                "SELECT installation_id FROM push_devices WHERE installation_id = ? AND user_id = ?",
                UUID.class, installationId, userId);
        if (devices.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "Push device not found");
        OffsetDateTime now = now();
        List<Presence> existing = find(userId, sessionId, installationId);
        if (!existing.isEmpty()) {
            Presence previous = existing.getFirst();
            if (previous.eventId().equals(eventId)) {
                if (previous.sequence() == sequence && previous.state().equals(state)) {
                    return result(previous, now);
                }
                throw new ApiException(409, "PRESENCE_EVENT_REUSED", "Event ID was reused with different input");
            }
            if (sequence <= previous.sequence()) {
                throw new ApiException(409, "PRESENCE_SEQUENCE_CONFLICT", "Presence sequence must increase");
            }
        }
        if ("BACKGROUND".equals(state) && !"STUDYING".equals(statuses.getFirst())) {
            throw new ApiException(409, "STUDY_NOT_RUNNING", "Only a running study can become backgrounded");
        }
        if (existing.isEmpty()) {
            OffsetDateTime awayAt = "BACKGROUND".equals(state) ? now : null;
            OffsetDateTime dueAt = awayAt == null ? null : awayAt.plusMinutes(5);
            String delivery = awayAt == null ? "NONE" : "PENDING";
            jdbc.update("INSERT INTO study_app_presence (session_id,installation_id,user_id,last_event_id,event_sequence,"
                            + "state,away_at,due_at,delivery_status,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    sessionId, installationId, userId, eventId, sequence, state, awayAt, dueAt, delivery, now);
        } else {
            Presence previous = existing.getFirst();
            if ("FOREGROUND".equals(state)) {
                jdbc.update("UPDATE study_app_presence SET last_event_id=?,event_sequence=?,state='FOREGROUND',"
                                + "away_at=NULL,due_at=NULL,delivery_status='CANCELED',claim_id=NULL,"
                                + "claimed_at=NULL,updated_at=? WHERE session_id=? AND installation_id=? AND user_id=?",
                        eventId, sequence, now, sessionId, installationId, userId);
            } else if ("BACKGROUND".equals(previous.state())
                    && !List.of("CANCELED", "NONE").contains(previous.deliveryStatus())) {
                // The five-minute clock starts at the first departure, not at each repeated event.
                jdbc.update("UPDATE study_app_presence SET last_event_id=?,event_sequence=?,updated_at=? "
                                + "WHERE session_id=? AND installation_id=? AND user_id=?",
                        eventId, sequence, now, sessionId, installationId, userId);
            } else {
                jdbc.update("UPDATE study_app_presence SET last_event_id=?,event_sequence=?,state='BACKGROUND',"
                                + "away_at=?,due_at=?,delivery_status='PENDING',claim_id=NULL,claimed_at=NULL,"
                                + "expo_ticket_id=NULL,updated_at=? WHERE session_id=? AND installation_id=? AND user_id=?",
                        eventId, sequence, now, now.plusMinutes(5), now, sessionId, installationId, userId);
            }
        }
        return result(find(userId, sessionId, installationId).getFirst(), now);
    }

    /** Called inside the same owner-locked transaction as a study transition. */
    public void cancelOnStudyTransition(UUID userId, UUID sessionId, OffsetDateTime now) {
        jdbc.update("UPDATE study_app_presence SET delivery_status='CANCELED',due_at=NULL,claim_id=NULL,"
                        + "claimed_at=NULL,updated_at=? WHERE user_id=? AND session_id=? "
                        + "AND delivery_status IN ('PENDING','SENDING')",
                now, userId, sessionId);
    }

    private List<Presence> find(UUID userId, UUID sessionId, UUID installationId) {
        return jdbc.query("SELECT session_id,installation_id,user_id,last_event_id,event_sequence,state,away_at,"
                        + "due_at,delivery_status FROM study_app_presence "
                        + "WHERE user_id=? AND session_id=? AND installation_id=?",
                (rs, row) -> new Presence(PortableJdbc.uuid(rs,"session_id"),
                        PortableJdbc.uuid(rs,"installation_id"), PortableJdbc.uuid(rs,"user_id"),
                        PortableJdbc.uuid(rs,"last_event_id"), rs.getLong("event_sequence"),
                        rs.getString("state"), PortableJdbc.utc(rs,"away_at"), PortableJdbc.utc(rs,"due_at"),
                        rs.getString("delivery_status")), userId, sessionId, installationId);
    }

    private PresenceResult result(Presence p, OffsetDateTime now) {
        return new PresenceResult(p.sessionId(), p.installationId(), p.state(), p.awayAt(), p.dueAt(),
                p.deliveryStatus(), deliveryEnabled, now);
    }

    private void lockUser(UUID userId) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, userId).isEmpty()) {
            throw new ApiException(401, "AUTH_REQUIRED", "Unknown user");
        }
    }

    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
