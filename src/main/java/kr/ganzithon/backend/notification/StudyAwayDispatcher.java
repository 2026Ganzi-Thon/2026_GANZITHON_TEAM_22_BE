package kr.ganzithon.backend.notification;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class StudyAwayDispatcher {
    private final PortableJdbc jdbc;
    private final TransactionTemplate transactions;
    private final PushSender sender;
    private final Clock clock;
    private final boolean enabled;

    public StudyAwayDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions, PushSender sender,
                               Clock clock, @Value("${ganzi.push.enabled:false}") boolean enabled) {
        this.jdbc = new PortableJdbc(jdbc);
        this.transactions = transactions;
        this.sender = sender;
        this.clock = clock;
        this.enabled = enabled;
    }

    private record Candidate(UUID userId, UUID sessionId, UUID installationId) { }
    private record Claimed(Candidate candidate, UUID claimId, String token) { }
    private record State(String status, String studyStatus, String presenceState, String token) { }

    @Scheduled(fixedDelayString = "${ganzi.push.dispatch-interval-ms:15000}")
    public void dispatch() {
        if (!enabled) return;
        OffsetDateTime now = now();
        List<Candidate> candidates = jdbc.query("SELECT user_id,session_id,installation_id FROM study_app_presence "
                        + "WHERE delivery_status='PENDING' AND due_at <= ? ORDER BY due_at LIMIT 20",
                (rs,row) -> new Candidate(PortableJdbc.uuid(rs,"user_id"),
                        PortableJdbc.uuid(rs,"session_id"), PortableJdbc.uuid(rs,"installation_id")), now);
        for (Candidate candidate : candidates) {
            Claimed claimed = transactions.execute(tx -> claim(candidate));
            if (claimed == null) continue;
            // Recheck in a separate short transaction. No database lock is held during HTTP.
            Boolean valid = transactions.execute(tx -> preflight(claimed));
            if (!Boolean.TRUE.equals(valid)) continue;
            PushSender.Result result = sender.send(claimed.token(), claimed.candidate().sessionId());
            transactions.executeWithoutResult(tx -> settle(claimed, result));
        }
        transactions.executeWithoutResult(tx -> jdbc.update(
                "UPDATE study_app_presence SET delivery_status='DELIVERY_UNKNOWN',claim_id=NULL,claimed_at=NULL,"
                        + "updated_at=? WHERE delivery_status='SENDING' AND claimed_at < ?",
                now(), now().minusMinutes(1)));
    }

    private Claimed claim(Candidate c) {
        if (!lockOwner(c.userId())) return null;
        State state = state(c);
        if (state == null || !"PENDING".equals(state.status()) || !"STUDYING".equals(state.studyStatus())
                || !"BACKGROUND".equals(state.presenceState()) || hasForeground(c)) {
            cancel(c);
            return null;
        }
        UUID claimId = UUID.randomUUID();
        int count = jdbc.update("UPDATE study_app_presence SET delivery_status='SENDING',claim_id=?,claimed_at=?,"
                        + "updated_at=? WHERE user_id=? AND session_id=? AND installation_id=? "
                        + "AND delivery_status='PENDING' AND due_at <= ?",
                claimId, now(), now(), c.userId(), c.sessionId(), c.installationId(), now());
        return count == 1 ? new Claimed(c,claimId,state.token()) : null;
    }

    private boolean preflight(Claimed claimed) {
        Candidate c = claimed.candidate();
        if (!lockOwner(c.userId())) return false;
        State state = state(c);
        if (state == null || !"SENDING".equals(state.status()) || !"STUDYING".equals(state.studyStatus())
                || !"BACKGROUND".equals(state.presenceState()) || hasForeground(c)) {
            cancel(c);
            return false;
        }
        List<UUID> ids = jdbc.queryForList("SELECT claim_id FROM study_app_presence WHERE user_id=? "
                + "AND session_id=? AND installation_id=?", UUID.class, c.userId(), c.sessionId(), c.installationId());
        if (ids.isEmpty() || !claimed.claimId().equals(ids.getFirst())) return false;
        if (!claimed.token().equals(state.token())) {
            cancel(c);
            return false;
        }
        return true;
    }

    private void settle(Claimed claimed, PushSender.Result result) {
        Candidate c=claimed.candidate();
        if (!lockOwner(c.userId())) return;
        jdbc.update("UPDATE study_app_presence SET delivery_status=?,expo_ticket_id=?,claim_id=NULL,"
                        + "claimed_at=NULL,updated_at=? WHERE user_id=? AND session_id=? AND installation_id=? "
                        + "AND delivery_status='SENDING' AND claim_id=?",
                result.status(), result.ticketId(), now(), c.userId(), c.sessionId(), c.installationId(),
                claimed.claimId());
    }

    private State state(Candidate c) {
        List<State> rows=jdbc.query("SELECT p.delivery_status,s.status AS study_status,p.state,d.expo_push_token "
                        + "FROM study_app_presence p JOIN study_sessions s ON s.id=p.session_id AND s.user_id=p.user_id "
                        + "JOIN push_devices d ON d.installation_id=p.installation_id AND d.user_id=p.user_id "
                        + "WHERE p.user_id=? AND p.session_id=? AND p.installation_id=?",
                (rs,row)->new State(rs.getString("delivery_status"),rs.getString("study_status"),
                        rs.getString("state"),rs.getString("expo_push_token")),
                c.userId(),c.sessionId(),c.installationId());
        return rows.isEmpty()?null:rows.getFirst();
    }

    private boolean hasForeground(Candidate c) {
        return !jdbc.queryForList("SELECT installation_id FROM study_app_presence "
                        + "WHERE user_id=? AND session_id=? AND state='FOREGROUND' LIMIT 1",
                UUID.class,c.userId(),c.sessionId()).isEmpty();
    }
    private void cancel(Candidate c) {
        jdbc.update("UPDATE study_app_presence SET delivery_status='CANCELED',due_at=NULL,claim_id=NULL,"
                        + "claimed_at=NULL,updated_at=? WHERE user_id=? AND session_id=? AND installation_id=? "
                        + "AND delivery_status IN ('PENDING','SENDING')",
                now(),c.userId(),c.sessionId(),c.installationId());
    }
    private boolean lockOwner(UUID id) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,id).isEmpty();
    }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
}
