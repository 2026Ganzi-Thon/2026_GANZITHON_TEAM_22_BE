package kr.ganzithon.backend.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiErrorHandlerTest {
    private static final String REQUEST_ID = "5093eb67-ce98-461b-a676-f647981394f1";
    private MockMvc mvc;
    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach void setup() {
        logger = (Logger) LoggerFactory.getLogger(ApiErrorHandler.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        mvc = MockMvcBuilders.standaloneSetup(new InputController())
            .setControllerAdvice(new ApiErrorHandler()).build();
    }

    @AfterEach void teardown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test void unsupportedBodyTypeIsAClientErrorWithoutEchoingInput() throws Exception {
        mvc.perform(post("/input").requestAttr("requestId", REQUEST_ID)
                .contentType(MediaType.TEXT_PLAIN).content("private-input"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(content().string(not(containsString("private-input"))));
    }

    @Test void unsupportedResponseTypeIsAClientErrorWithAReadableJsonError() throws Exception {
        mvc.perform(post("/input").requestAttr("requestId", REQUEST_ID)
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                .content("{\"value\":\"private-input\"}"))
            .andExpect(status().isNotAcceptable())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test void methodNotAllowedIncludesTheSupportedMethod() throws Exception {
        mvc.perform(get("/input").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", containsString("POST")))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test void invalidJsonRemainsBadRequest() throws Exception {
        mvc.perform(post("/input").requestAttr("requestId", REQUEST_ID)
                .contentType(MediaType.APPLICATION_JSON).content("{invalid"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test void unexpectedFailureNeverExposesExceptionDetails() throws Exception {
        mvc.perform(get("/failure").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
            .andExpect(jsonPath("$.retryable").value(true))
            .andExpect(content().string(not(containsString("private-internal-detail"))));
        assertSafeFailureLog("java.lang.IllegalStateException");
    }

    @Test void internalIllegalArgumentIsNotReportedAsBadInput() throws Exception {
        mvc.perform(get("/internal-argument").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertSafeFailureLog("java.lang.IllegalArgumentException");
    }

    @Test void failureLogDoesNotRecordRequestBodyHeaderOrCause() throws Exception {
        mvc.perform(post("/sensitive-failure").requestAttr("requestId", REQUEST_ID)
                .header("X-Private-Header", "private-header-detail")
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"private-request-body\"}"))
            .andExpect(status().isInternalServerError());
        assertSafeFailureLog("java.lang.IllegalStateException");
        String message = logs.list.get(logs.list.size() - 1).getFormattedMessage();
        assertFalse(message.contains("private-request-body"));
        assertFalse(message.contains("private-header-detail"));
        assertFalse(message.contains("private-cause-detail"));
    }

    @Test void unidentifiedDatabaseConstraintIsNotReportedAsClientConflict() throws Exception {
        mvc.perform(get("/internal-integrity").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        assertSafeFailureLog("org.springframework.dao.DataIntegrityViolationException");
    }

    @Test void knownBusinessConflictKeepsItsExplicitStatus() throws Exception {
        mvc.perform(get("/business-conflict").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_REGISTERED"));
        assertTrue(logs.list.isEmpty());
    }

    @Test void definitiveCorruptionIsNotMarkedRetryableEvenThoughTheStatusIs503() throws Exception {
        mvc.perform(get("/corrupt").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CHAT_HISTORY_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test void temporaryHistoryFailureRemainsRetryable() throws Exception {
        mvc.perform(get("/temporary").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CHAT_HISTORY_UNAVAILABLE"))
            .andExpect(jsonPath("$.retryable").value(true));
        assertSafeFailureLog("kr.ganzithon.backend.common.ApiException");
    }

    @Test void expectedUnconfiguredChatKeepsApiErrorWithoutStackLogNoise() throws Exception {
        mvc.perform(get("/unconfigured-chat").requestAttr("requestId", REQUEST_ID))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CHAT_NOT_CONFIGURED"))
            .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
        assertTrue(logs.list.isEmpty());
    }

    private void assertSafeFailureLog(String expectedClass) {
        assertFalse(logs.list.isEmpty());
        ILoggingEvent event = logs.list.get(logs.list.size() - 1);
        String message = event.getFormattedMessage();
        assertTrue(message.contains("requestId=" + REQUEST_ID));
        assertTrue(message.contains("exception=" + expectedClass));
        assertTrue(message.contains("frames=["));
        assertFalse(message.contains("private-internal-detail"));
        assertFalse(message.contains("private-sql-detail"));
        assertFalse(message.contains("Saved conversation"));
        assertFalse(message.contains("Conversation history"));
        assertNull(event.getThrowableProxy());
    }

    @RestController
    static class InputController {
        @PostMapping(value = "/input", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> input(@RequestBody Map<String, String> body) { return body; }

        @GetMapping("/failure")
        Map<String, String> fail() { throw new IllegalStateException("private-internal-detail"); }

        @GetMapping("/internal-argument")
        Map<String, String> internalArgument() { throw new IllegalArgumentException("private-internal-detail"); }

        @GetMapping("/internal-integrity")
        Map<String, String> internalIntegrity() { throw new DataIntegrityViolationException("private-sql-detail"); }

        @PostMapping("/sensitive-failure")
        Map<String, String> sensitiveFailure(@RequestBody Map<String, String> body) {
            throw new IllegalStateException("private-internal-detail", new IllegalStateException("private-cause-detail"));
        }

        @GetMapping("/business-conflict")
        Map<String, String> businessConflict() { throw new ApiException(409, "ACCOUNT_ALREADY_REGISTERED", "이미 등록됨"); }

        @GetMapping("/corrupt")
        Map<String, String> corrupt() { throw new ApiException(503, "CHAT_HISTORY_UNAVAILABLE", "Saved conversation could not be read safely", false); }

        @GetMapping("/temporary")
        Map<String, String> temporary() { throw new ApiException(503, "CHAT_HISTORY_UNAVAILABLE", "Conversation history is temporarily unavailable"); }

        @GetMapping("/unconfigured-chat")
        Map<String, String> unconfiguredChat() { throw new ApiException(503, "CHAT_NOT_CONFIGURED", "AI chat is not configured"); }
    }
}
