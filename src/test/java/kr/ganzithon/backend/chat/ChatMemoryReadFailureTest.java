package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryReadFailureTest {
    @Test void readOperationsMapDatabaseOutageToRetryable503() {
        ChatMemoryService memory = new ChatMemoryService(failingJdbc(new DataAccessResourceFailureException("database offline")),
                new ObjectMapper(), Clock.systemUTC());
        UUID owner = UUID.randomUUID();
        assertUnavailable(() -> memory.find(owner, UUID.randomUUID()));
        assertUnavailable(() -> memory.latest(owner, null));
        assertUnavailable(() -> memory.history(owner, 10, null));
        assertUnavailable(() -> memory.context(owner, "공부할게", null));
    }

    @Test void unrelatedProgrammingFailureIsNotConvertedToDatabaseOutage() {
        IllegalStateException bug = new IllegalStateException("unexpected mapper bug");
        ChatMemoryService memory = new ChatMemoryService(failingJdbc(bug), new ObjectMapper(), Clock.systemUTC());
        assertEquals(bug, assertThrows(IllegalStateException.class,
                () -> memory.history(UUID.randomUUID(), 10, null)));
    }

    private static void assertUnavailable(Runnable request) {
        ApiException error = assertThrows(ApiException.class, request::run);
        assertEquals(503, error.status());
        assertEquals("CHAT_HISTORY_UNAVAILABLE", error.code());
        assertTrue(error.retryable());
    }

    private static JdbcTemplate failingJdbc(RuntimeException failure) {
        return new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                throw failure;
            }
        };
    }
}
