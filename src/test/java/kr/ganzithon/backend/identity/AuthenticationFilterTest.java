package kr.ganzithon.backend.identity;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void authenticationLookupFailureHasSafeJsonAndCorrelatedLog() throws Exception {
        IdentityService identity = mock(IdentityService.class);
        when(identity.authenticate(anyString())).thenThrow(new DataAccessResourceFailureException(
            "private SQL and secret token"));
        AuthenticationFilter filter = new AuthenticationFilter(identity, mapper, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tasks");
        request.addHeader("Authorization", "Bearer " + "x".repeat(43));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chained = new AtomicBoolean();

        Logger logger = (Logger) LoggerFactory.getLogger(AuthenticationFilter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            filter.doFilter(request, response, (req, res) -> chained.set(true));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }

        assertFalse(chained.get());
        assertEquals(500, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        String requestId = response.getHeader("X-Request-ID");
        assertNotNull(requestId);
        var body = mapper.readTree(response.getContentAsString());
        assertEquals("INTERNAL_ERROR", body.get("code").asText());
        assertEquals(requestId, body.get("requestId").asText());
        assertTrue(body.get("retryable").asBoolean());
        assertFalse(response.getContentAsString().contains("private SQL"));
        assertEquals(1, logs.list.size());
        assertTrue(logs.list.getFirst().getFormattedMessage().contains("requestId=" + requestId));
        assertTrue(logs.list.getFirst().getFormattedMessage().contains("code=INTERNAL_ERROR"));
        assertFalse(logs.list.getFirst().getFormattedMessage().contains("private SQL"));
        assertNull(logs.list.getFirst().getThrowableProxy());
    }

    @Test void downstreamFailureIsNotRewrittenByTheAuthenticationFilter() {
        AuthenticationFilter filter = new AuthenticationFilter(mock(IdentityService.class), mapper, false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(ApiException.class, () -> filter.doFilter(request, response,
            (req, res) -> { throw new ApiException(503, "DOWNSTREAM_FAILURE", "downstream detail"); }));
        assertEquals(200, response.getStatus());
    }
}
