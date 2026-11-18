package kr.ganzithon.backend.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeExceptionLoggerTest {
    @Test void failureCodeIsSingleLineAndUntrustedCodeIsDiscarded() {
        Logger logger = (Logger) LoggerFactory.getLogger(SafeExceptionLoggerTest.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            SafeExceptionLogger.event(logger, "5093eb67-ce98-461b-a676-f647981394f1", "AI_PROVIDER_FAILED");
            SafeExceptionLogger.event(logger, "private-id", "BAD\nsecret");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
        assertEquals(2, logs.list.size());
        assertTrue(logs.list.get(0).getFormattedMessage().contains("code=AI_PROVIDER_FAILED"));
        assertTrue(logs.list.get(1).getFormattedMessage().contains("requestId=unknown code=UNKNOWN_ERROR"));
        assertFalse(logs.list.get(1).getFormattedMessage().contains("secret"));
        assertNull(logs.list.get(0).getThrowableProxy());
    }
}
