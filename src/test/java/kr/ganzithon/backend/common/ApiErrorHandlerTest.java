package kr.ganzithon.backend.common;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiErrorHandlerTest {
    private static final String REQUEST_ID = "5093eb67-ce98-461b-a676-f647981394f1";
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new InputController())
            .setControllerAdvice(new ApiErrorHandler()).build();
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
    }

    @RestController
    static class InputController {
        @PostMapping(value = "/input", produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> input(@RequestBody Map<String, String> body) { return body; }

        @GetMapping("/failure")
        Map<String, String> fail() { throw new IllegalStateException("private-internal-detail"); }

        @GetMapping("/corrupt")
        Map<String, String> corrupt() { throw new ApiException(503, "CHAT_HISTORY_UNAVAILABLE", "Saved conversation could not be read safely", false); }

        @GetMapping("/temporary")
        Map<String, String> temporary() { throw new ApiException(503, "CHAT_HISTORY_UNAVAILABLE", "Conversation history is temporarily unavailable"); }
    }
}
