package kr.ganzithon.backend.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import kr.ganzithon.backend.chat.ChatService;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class IdentityService {
    private final PortableJdbc jdbc;
    private final Clock clock;
    private final Duration lifetime;
    private final ChatService chat;
    private final SecureRandom random = new SecureRandom();
    public IdentityService(JdbcTemplate jdbc, Clock clock, ChatService chat,
                           @Value("${ganzi.auth.days:30}") long days) {
        this.jdbc=new PortableJdbc(jdbc); this.clock=clock; this.chat=chat; this.lifetime=Duration.ofDays(days);
    }
    public record GuestDTO(UUID userId, String accessToken, Instant expiresAt) {}
    public record MeDTO(UUID userId, Instant expiresAt, String accountType,
                        String email, boolean emailVerified) {}
    public record AuthSessionDTO(UUID userId, String accessToken, Instant expiresAt,
                                 String accountType, String email, boolean emailVerified) {}
    @Transactional public GuestDTO create() {
        Instant now=clock.instant();
        UUID userId=UUID.randomUUID();
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt=now.plus(lifetime);
        jdbc.update("INSERT INTO users(id,created_at) VALUES(?,?)", userId, Timestamp.from(now));
        jdbc.update("INSERT INTO auth_sessions(id,user_id,secret_hash,expires_at,created_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),userId,sha256(secret),Timestamp.from(expiresAt),Timestamp.from(now));
        return new GuestDTO(userId,secret,expiresAt);
    }
    public MeDTO authenticate(String token) {
        if (token==null || token.length()!=43 || !token.matches("[A-Za-z0-9_-]+")) throw unauthorized();
        var result=jdbc.query("SELECT s.user_id,s.expires_at,a.normalized_email,a.email_verified "
                + "FROM auth_sessions s LEFT JOIN email_accounts a ON a.user_id=s.user_id "
                + "WHERE s.secret_hash=? AND s.revoked_at IS NULL",
            (rs,i)->new MeDTO(PortableJdbc.uuid(rs,"user_id"),PortableJdbc.instant(rs,"expires_at"),
                rs.getString("normalized_email") == null ? "GUEST" : "EMAIL",
                rs.getString("normalized_email"),rs.getBoolean("email_verified")),
            sha256(token));
        if (result.isEmpty()) throw unauthorized();
        if (!result.getFirst().expiresAt().isAfter(clock.instant()))
            throw new ApiException(401,"AUTH_EXPIRED","인증 정보가 만료됐습니다.");
        return result.getFirst();
    }

    AuthSessionDTO issueSession(UUID userId, String email, boolean emailVerified) {
        Instant now=clock.instant();
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt=now.plus(lifetime);
        jdbc.update("INSERT INTO auth_sessions(id,user_id,secret_hash,expires_at,created_at) VALUES(?,?,?,?,?)",
            UUID.randomUUID(),userId,sha256(secret),Timestamp.from(expiresAt),Timestamp.from(now));
        return new AuthSessionDTO(userId,secret,expiresAt,"EMAIL",email,emailVerified);
    }

    String tokenHash(String token) { return sha256(token); }
    @Transactional
    public void deleteCurrentUser(UUID userId) {
        // Serialize deletion with task/study writes; never recreate a deleted owner.
        if (jdbc.query("SELECT id FROM users WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> PortableJdbc.uuid(rs, 1), userId).isEmpty()) {
            throw unauthorized();
        }
        jdbc.update("DELETE FROM study_sessions WHERE user_id = ?", userId);
        // The task FK cascades to the owner's subtasks.
        jdbc.update("DELETE FROM tasks WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM email_accounts WHERE user_id = ?", userId);
        // Retain anonymous billing totals while removing the deleted account's request fingerprints.
        jdbc.update("UPDATE chat_usage_events SET request_hash=NULL WHERE user_id=?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                chat.forgetUser(userId);
            }
        });
    }
    private ApiException unauthorized() { return new ApiException(401,"AUTH_REQUIRED","인증 정보가 올바르지 않습니다."); }
    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
