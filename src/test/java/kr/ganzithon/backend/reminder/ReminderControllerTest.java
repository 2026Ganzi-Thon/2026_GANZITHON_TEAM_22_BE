package kr.ganzithon.backend.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReminderControllerTest {
    private final ReminderService service = mock(ReminderService.class);
    private final ReminderController controller = new ReminderController(service);
    private final UUID user = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final OffsetDateTime time = OffsetDateTime.parse("2026-11-19T10:00:00Z");
    private final String zone = "Asia/Seoul";
    private final JsonNode body = new ObjectMapper().createObjectNode()
            .put("remindAt", time.toString()).put("timeZone", zone);

    @Test void transientFailureRetriesOnceAndReturnsSavedReminder() {
        var reminder = new ReminderDto.StartReminder(UUID.randomUUID(), session, time, zone,
                "ACTIVE", null, time.minusDays(1), time.minusDays(1));
        when(service.put(user, session, time, zone)).thenThrow(new CannotAcquireLockException("busy"))
                .thenReturn(new ReminderService.PutResult(reminder, time.minusDays(1), true));
        var response = controller.put(user, session, body);
        assertEquals(201, response.getStatusCode().value());
        assertEquals(reminder, response.getBody().reminder());
        verify(service, times(2)).put(user, session, time, zone);
    }

    @Test void repeatedLockFailureReturnsRetryable503AfterTwoAttempts() {
        when(service.put(user, session, time, zone)).thenThrow(new CannotAcquireLockException("busy"));
        assertRetryableUnavailable();
        verify(service, times(2)).put(user, session, time, zone);
    }

    @Test void mysqlLockWaitTimeoutReturnsRetryable503AfterOneAttempt() {
        var timeout = new SQLException("Lock wait timeout exceeded", "HY000", 1205);
        when(service.put(user, session, time, zone)).thenThrow(new CannotAcquireLockException("busy", timeout));
        assertRetryableUnavailable();
        verify(service).put(user, session, time, zone);
    }

    @Test void reminderConflictPropagatesAfterOneAttempt() {
        var conflict = new ApiException(409, "REMINDER_REQUEST_CONFLICT", "different reminder");
        when(service.put(user, session, time, zone)).thenThrow(conflict);
        assertSame(conflict, assertThrows(ApiException.class, () -> controller.put(user, session, body)));
        verify(service).put(user, session, time, zone);
    }

    private void assertRetryableUnavailable() {
        ApiException failure = assertThrows(ApiException.class, () -> controller.put(user, session, body));
        assertEquals(503, failure.status());
        assertEquals("REMINDER_TEMPORARILY_UNAVAILABLE", failure.code());
        assertTrue(failure.retryable());
    }
}
