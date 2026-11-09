package kr.ganzithon.backend.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void skipsDatabaseWhenDisabled() {
        assertEquals(Map.of("status", "UP"), new HealthController(jdbc, false).health());
        verifyNoInteractions(jdbc);
    }

    @Test
    void returnsUpWhenDatabaseResponds() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertEquals(Map.of("status", "UP"), new HealthController(jdbc, true).health());
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void hidesDatabaseFailureAndRejectsUnexpectedResult() {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("secret host and password"))
                .thenReturn(0);
        HealthController controller = new HealthController(jdbc, true);

        for (int i = 0; i < 2; i++) {
            ApiException failure = assertThrows(ApiException.class, controller::health);
            assertEquals(503, failure.status());
            assertEquals("DATABASE_NOT_READY", failure.code());
            assertEquals("서비스를 준비하는 중입니다.", failure.getMessage());
        }
    }
}
