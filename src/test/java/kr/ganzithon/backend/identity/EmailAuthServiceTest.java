package kr.ganzithon.backend.identity;

import java.time.Clock;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailAuthServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = new TransactionTemplate() {
        @Override public <T> T execute(TransactionCallback<T> callback) {
            return callback.doInTransaction(new SimpleTransactionStatus());
        }
    };
    private final EmailAuthService service = new EmailAuthService(jdbc, transactions,
        mock(IdentityService.class), mock(PasswordHasher.class), Clock.systemUTC());

    @Test void existingEmailIsReportedAsAConflict() {
        when(jdbc.update(startsWith("INSERT INTO email_accounts"), any(Object[].class)))
            .thenThrow(new DuplicateKeyException("private duplicate value"));

        ApiException failure = assertThrows(ApiException.class, this::register);
        assertEquals(409, failure.status());
        assertEquals("EMAIL_ALREADY_REGISTERED", failure.code());
    }

    @Test void unrelatedIntegrityFailuresPropagateToTheServerErrorHandler() {
        var nonUniqueConstraint = new DataIntegrityViolationException("private SQL");
        var duplicateUserKey = new DuplicateKeyException("private user ID");
        when(jdbc.update(startsWith("INSERT INTO email_accounts"), any(Object[].class)))
            .thenThrow(nonUniqueConstraint);

        assertSame(nonUniqueConstraint, assertThrows(DataIntegrityViolationException.class, this::register));

        when(jdbc.update(startsWith("INSERT INTO users"), any(Object[].class)))
            .thenThrow(duplicateUserKey);

        assertSame(duplicateUserKey, assertThrows(DuplicateKeyException.class, this::register));
    }

    private void register() {
        service.register("student@example.org", "valid-test-password", null, null);
    }
}
