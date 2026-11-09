package kr.ganzithon.backend.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailAuthService {
    private final PortableJdbc jdbc;
    private final TransactionTemplate transactions;
    private final IdentityService identity;
    private final PasswordHasher passwords;
    private final Clock clock;

    public EmailAuthService(JdbcTemplate jdbc, TransactionTemplate transactions,
                            IdentityService identity, PasswordHasher passwords, Clock clock) {
        this.jdbc=new PortableJdbc(jdbc); this.transactions=transactions; this.identity=identity;
        this.passwords=passwords; this.clock=clock;
    }

    public IdentityService.AuthSessionDTO register(String rawEmail, String password,
                                                    UUID guestId, String guestToken) {
        String email=normalizeEmail(rawEmail);
        requireNewPassword(password);
        String encoded=passwords.hash(password); // The expensive work happens before acquiring a DB lock.
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                UUID userId=guestId;
                Instant now=clock.instant();
                if (userId == null) {
                    userId=UUID.randomUUID();
                    jdbc.update("INSERT INTO users(id,created_at) VALUES(?,?)",userId,Timestamp.from(now));
                } else {
                    lockUser(userId);
                    requireCurrentSession(userId,guestToken);
                    if (!jdbc.query("SELECT user_id FROM email_accounts WHERE user_id=?",
                            (rs,i)->PortableJdbc.uuid(rs,1),userId).isEmpty()) {
                        throw new ApiException(409,"ACCOUNT_ALREADY_REGISTERED","이미 이메일 계정이 연결돼 있습니다.");
                    }
                }
                jdbc.update("INSERT INTO email_accounts(user_id,normalized_email,password_hash,email_verified,created_at,updated_at) "
                        + "VALUES(?,?,?,FALSE,?,?)",userId,email,encoded,Timestamp.from(now),Timestamp.from(now));
                jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",userId);
                return identity.issueSession(userId,email,false);
            }));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(409,"EMAIL_ALREADY_REGISTERED","이미 등록된 이메일입니다.");
        }
    }

    public IdentityService.AuthSessionDTO login(String rawEmail, String password) {
        String email=normalizeEmail(rawEmail);
        requireLoginPassword(password);
        Account observed=findByEmail(email);
        if (observed == null) {
            passwords.verifyDummy(password);
            throw invalidCredentials();
        }
        if (!passwords.verify(password,observed.passwordHash())) throw invalidCredentials();
        return Objects.requireNonNull(transactions.execute(status -> {
            lockUserForLogin(observed.userId());
            Account current=findByUser(observed.userId());
            if (current == null || !current.passwordHash().equals(observed.passwordHash())) {
                throw invalidCredentials();
            }
            return identity.issueSession(current.userId(),current.email(),current.emailVerified());
        }));
    }

    public void logout(UUID userId, String token) {
        transactions.executeWithoutResult(status -> {
            lockUser(userId);
            requireCurrentSession(userId,token);
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=? AND secret_hash=?",
                userId,identity.tokenHash(token));
        });
    }

    public IdentityService.AuthSessionDTO changePassword(UUID userId, String token,
                            String currentPassword, String newPassword) {
        requireLoginPassword(currentPassword);
        requireNewPassword(newPassword);
        Account observed=findByUser(userId);
        if (observed == null || !passwords.verify(currentPassword,observed.passwordHash())) {
            if (observed == null) passwords.verifyDummy(currentPassword);
            throw invalidCredentials();
        }
        String replacement=passwords.hash(newPassword);
        return Objects.requireNonNull(transactions.execute(status -> {
            lockUser(userId);
            requireCurrentSession(userId,token);
            Account current=findByUser(userId);
            if (current == null || !current.passwordHash().equals(observed.passwordHash())) {
                throw invalidCredentials();
            }
            jdbc.update("UPDATE email_accounts SET password_hash=?,updated_at=? WHERE user_id=?",
                replacement,Timestamp.from(clock.instant()),userId);
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",userId);
            return identity.issueSession(userId,current.email(),current.emailVerified());
        }));
    }

    private String normalizeEmail(String value) {
        if (value == null) throw invalidInput();
        String email=value.strip().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length()>254
            || !email.matches("[a-z0-9._%+\\-]+@[a-z0-9\\-]+(\\.[a-z0-9\\-]+)+")
            || email.contains("..") || email.startsWith(".") || email.contains(".@")) {
            throw invalidInput();
        }
        return email;
    }

    private void requireNewPassword(String value) {
        if (value == null || value.codePointCount(0,value.length())<15
            || value.codePointCount(0,value.length())>128) throw invalidInput();
    }

    private void requireLoginPassword(String value) {
        if (value == null || value.codePointCount(0,value.length())<1
            || value.codePointCount(0,value.length())>128) throw invalidInput();
    }

    private void lockUser(UUID userId) {
        if (jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",
                (rs,i)->PortableJdbc.uuid(rs,1),userId).isEmpty()) {
            throw new ApiException(401,"AUTH_REQUIRED","인증 정보가 올바르지 않습니다.");
        }
    }

    private void lockUserForLogin(UUID userId) {
        if (jdbc.query("SELECT id FROM users WHERE id=? FOR UPDATE",
                (rs,i)->PortableJdbc.uuid(rs,1),userId).isEmpty()) throw invalidCredentials();
    }

    private void requireCurrentSession(UUID userId, String token) {
        if (token == null) throw new ApiException(401,"AUTH_REQUIRED","인증이 필요합니다.");
        var valid=jdbc.query("SELECT expires_at FROM auth_sessions "
                + "WHERE user_id=? AND secret_hash=? AND revoked_at IS NULL",
            (rs,i)->PortableJdbc.instant(rs,1),userId,identity.tokenHash(token));
        if (valid.isEmpty() || !valid.getFirst().isAfter(clock.instant())) {
            throw new ApiException(401,"AUTH_REQUIRED","인증 정보가 올바르지 않습니다.");
        }
    }

    private Account findByEmail(String email) {
        var rows=jdbc.query("SELECT user_id,normalized_email,password_hash,email_verified "
                + "FROM email_accounts WHERE normalized_email=?",
            (rs,i)->new Account(PortableJdbc.uuid(rs,1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),email);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private Account findByUser(UUID userId) {
        var rows=jdbc.query("SELECT user_id,normalized_email,password_hash,email_verified "
                + "FROM email_accounts WHERE user_id=?",
            (rs,i)->new Account(PortableJdbc.uuid(rs,1),rs.getString(2),rs.getString(3),rs.getBoolean(4)),userId);
        return rows.isEmpty()?null:rows.getFirst();
    }

    private ApiException invalidCredentials() {
        return new ApiException(401,"INVALID_CREDENTIALS","이메일 또는 비밀번호가 올바르지 않습니다.");
    }
    private ApiException invalidInput() {
        return new ApiException(400,"VALIDATION_FAILED","이메일 또는 비밀번호 형식이 올바르지 않습니다.");
    }
    private record Account(UUID userId,String email,String passwordHash,boolean emailVerified) {}
}
