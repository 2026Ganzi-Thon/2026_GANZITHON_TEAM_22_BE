package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(BackendApiIntegrationTest.ClockConfig.class)
class BackendApiIntegrationTest {
    private static final String TASKS = "/api/v1/tasks";
    private static final String STUDIES = "/api/v1/study-sessions";
    private static final Instant START = Instant.parse("2026-11-03T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;

    @BeforeEach void resetClock() { clock.set(START); }

    @Test void publicHealthAndGuestAuthentication() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        String token = guest();
        JsonNode me = json(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        assertTrue(me.path("userId").isTextual());
        assertTrue(me.path("expiresAt").isTextual());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(TASKS)).andExpect(status().isUnauthorized());
    }

    @Test void studyContinuesWithoutClientRequestsAcrossDaysUntilExplicitEnd() throws Exception {
        String token = guest();
        JsonNode started = startStudy(token, null, UUID.randomUUID());
        String id = started.path("id").asText();

        // No client heartbeat is required; elapsed time comes from server timestamps.
        clock.advanceSeconds(26 * 3600);
        JsonNode current = json(mvc.perform(get(STUDIES + "/current")
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andReturn()).path("studySession");
        assertEquals(id, current.path("id").asText());
        assertEquals("STUDYING", current.path("status").asText());
        assertEquals(26 * 3600, elapsed(current));

        JsonNode ended = action(token, id, "END", current.path("version").asInt(),
            "USER_FINISHED", 200);
        assertEquals("ENDED", ended.path("status").asText());
        assertEquals(26 * 3600, elapsed(ended));
        clock.advanceSeconds(3600);
        JsonNode restored = json(mvc.perform(get(STUDIES + "/" + id)
                .header("Authorization", bearer(token)))
            .andExpect(status().isOk()).andReturn());
        assertEquals(26 * 3600, elapsed(restored));
    }

    @Test void guestTokenExpiresAccordingToServerClock() throws Exception {
        String token = guest();
        clock.advanceSeconds(366L * 24 * 3600);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test void taskPatchOwnershipAndDeletionRules() throws Exception {
        String owner = guest();
        String other = guest();
        JsonNode created = createTask(owner, "발표 자료", "초안", "2026-11-10");
        String id = created.path("id").asText();
        assertEquals("TODO", created.path("status").asText());
        assertEquals(0, created.path("subtaskSummary").path("total").asInt());

        mvc.perform(get(TASKS + "/" + id).header("Authorization", bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(patch(TASKS + "/" + id).header("Authorization", bearer(other))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"침범\"}"))
                .andExpect(status().isNotFound());

        JsonNode patch = request(patch(TASKS + "/" + id), owner,
                "{\"description\":null,\"status\":\"DONE\"}", 200);
        assertEquals("발표 자료", patch.path("title").asText());
        assertTrue(patch.path("description").isNull());
        assertEquals("2026-11-10", patch.path("dueDate").asText());
        assertEquals("DONE", patch.path("status").asText());
        assertTrue(patch.path("completedAt").isTextual());

        request(post(STUDIES), owner,
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"taskId\":\"" + id + "\"}", 409);

        JsonNode reopened = request(patch(TASKS + "/" + id), owner,
                "{\"status\":\"TODO\",\"dueDate\":null}", 200);
        assertTrue(reopened.path("completedAt").isNull());
        assertTrue(reopened.path("dueDate").isNull());
        assertEquals("발표 자료", reopened.path("title").asText());

        JsonNode study = startStudy(owner, id, UUID.randomUUID());
        mvc.perform(delete(TASKS + "/" + id).header("Authorization", bearer(owner)))
                .andExpect(status().isConflict());
        String sid = study.path("id").asText();
        action(owner, sid, "END", 0, "USER_FINISHED", 200);
        mvc.perform(delete(TASKS + "/" + id).header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());
        JsonNode detached = json(mvc.perform(get(STUDIES + "/" + sid).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andReturn());
        assertTrue(detached.path("taskId").isNull());
    }

    @Test void studyIdempotencyTimingAndVersionTransitions() throws Exception {
        String token = guest();
        UUID key = UUID.randomUUID();
        JsonNode initial = request(post(STUDIES), token,
                "{\"clientRequestId\":\"" + key + "\"}", 201);
        String sid = initial.path("id").asText();
        assertTrue(initial.path("taskId").isNull());
        assertEquals(0, initial.path("version").asInt());
        assertEquals("STUDYING", initial.path("status").asText());

        JsonNode retry = request(post(STUDIES), token,
                "{\"clientRequestId\":\"" + key + "\",\"taskId\":null}", 200);
        assertEquals(sid, retry.path("id").asText());

        JsonNode task = createTask(token, "다른 할 일", null, null);
        request(post(STUDIES), token,
                "{\"clientRequestId\":\"" + key + "\",\"taskId\":\"" + task.path("id").asText() + "\"}", 409);
        request(post(STUDIES), token,
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\"}", 409);

        clock.advanceSeconds(600);
        JsonNode running = json(mvc.perform(get(STUDIES + "/" + sid).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        assertEquals("STUDYING", running.path("status").asText());
        assertEquals(600, elapsed(running));

        JsonNode paused = action(token, sid, "PAUSE", 0, null, 200);
        assertEquals(1, paused.path("version").asInt());
        clock.advanceSeconds(300);
        JsonNode resumed = action(token, sid, "RESUME", 1, null, 200);
        assertEquals(2, resumed.path("version").asInt());
        action(token, sid, "PAUSE", 1, null, 409);
        clock.advanceSeconds(180);
        JsonNode ended = action(token, sid, "END", 2, "USER_FINISHED", 200);
        assertEquals(3, ended.path("version").asInt());
        assertEquals("ENDED", ended.path("status").asText());
        assertEquals("USER_FINISHED", ended.path("endReason").asText());
        assertEquals(780, elapsed(ended));

        clock.advanceSeconds(100);
        JsonNode endRetry = action(token, sid, "END", 2, "USER_STOPPED", 200);
        assertEquals(3, endRetry.path("version").asInt());
        assertEquals("USER_FINISHED", endRetry.path("endReason").asText());
        assertEquals(780, elapsed(endRetry));
        action(token, sid, "RESUME", 3, null, 409);
    }

    @Test void malformedInputsAndPrivateStudyAreRejected() throws Exception {
        String owner = guest();
        String other = guest();
        JsonNode study = startStudy(owner, null, UUID.randomUUID());
        mvc.perform(get(STUDIES + "/" + study.path("id").asText())
                .header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(get(STUDIES + "/not-a-uuid").header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
        request(post(STUDIES), owner, "{broken", 400);
        action(owner, study.path("id").asText(), "PAUSE", -1, null, 400);
        request(patch(TASKS + "/not-a-uuid"), owner, "{\"title\":\"x\"}", 400);
    }

    @Test void concurrentStartsCreateExactlyOneActiveStudy() throws Exception {
        String token = guest();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        MvcResult[] results = concurrentStarts(token, first, second);
        int firstStatus = results[0].getResponse().getStatus();
        int secondStatus = results[1].getResponse().getStatus();
        assertTrue((firstStatus == 201 && secondStatus == 409)
                || (firstStatus == 409 && secondStatus == 201),
                "one concurrent start must be created and the other rejected");
        JsonNode current = json(mvc.perform(get(STUDIES + "/current")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        assertEquals("STUDYING", current.path("studySession").path("status").asText());
    }

    @Test void concurrentReplayOfSameRequestCreatesOneStudy() throws Exception {
        String token = guest();
        UUID requestId = UUID.randomUUID();
        MvcResult[] results = concurrentStarts(token, requestId, requestId);
        int firstStatus = results[0].getResponse().getStatus();
        int secondStatus = results[1].getResponse().getStatus();
        assertTrue((firstStatus == 201 && secondStatus == 200)
                || (firstStatus == 200 && secondStatus == 201),
                "same request replay must return a created and a reused study");
        assertEquals(json(results[0]).path("id").asText(), json(results[1]).path("id").asText());
    }

    @Test void pauseReplayWithOldVersionDoesNotIncrementVersion() throws Exception {
        String token = guest();
        String sessionId = startStudy(token, null, UUID.randomUUID()).path("id").asText();
        clock.advanceSeconds(120);
        JsonNode paused = action(token, sessionId, "PAUSE", 0, null, 200);
        assertEquals(1, paused.path("version").asInt());
        clock.advanceSeconds(60);
        JsonNode replay = action(token, sessionId, "PAUSE", 0, null, 200);
        assertEquals(1, replay.path("version").asInt());
        assertEquals(120, elapsed(replay));
    }

    @Test void unknownFieldsAndExplicitNullViolationsReturnBadRequest() throws Exception {
        String token = guest();
        request(post(TASKS), token, "{\"title\":\"x\",\"unexpected\":1}", 400);
        request(post(TASKS), token, "{\"title\":null}", 400);
        JsonNode task = createTask(token, "초안", null, null);
        String taskId = task.path("id").asText();
        request(patch(TASKS + "/" + taskId), token, "{\"status\":null}", 400);
        request(patch(TASKS + "/" + taskId), token, "{\"title\":null}", 400);
        request(patch(TASKS + "/" + taskId), token, "{}", 400);
        request(post(STUDIES), token, "{\"clientRequestId\":null}", 400);
        request(post(STUDIES), token,
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"unexpected\":1}", 400);
        String sessionId = startStudy(token, null, UUID.randomUUID()).path("id").asText();
        request(patch(STUDIES + "/" + sessionId), token,
                "{\"action\":\"PAUSE\",\"expectedVersion\":0,\"endReason\":null}", 400);
        request(patch(STUDIES + "/" + sessionId), token,
                "{\"action\":\"END\",\"expectedVersion\":0,\"endReason\":null}", 400);
    }

    @Test void settingDoneTwiceKeepsOriginalCompletionTime() throws Exception {
        String token = guest();
        String taskId = createTask(token, "원고", null, null).path("id").asText();
        JsonNode done = request(patch(TASKS + "/" + taskId), token, "{\"status\":\"DONE\"}", 200);
        String completedAt = done.path("completedAt").asText();
        clock.advanceSeconds(3600);
        JsonNode repeated = request(patch(TASKS + "/" + taskId), token, "{\"status\":\"DONE\"}", 200);
        assertEquals(completedAt, repeated.path("completedAt").asText());
    }

    @Test void startReplayAfterTaskDeletionReturnsOriginalEndedSession() throws Exception {
        String token = guest();
        String taskId = createTask(token, "발표", null, null).path("id").asText();
        UUID requestId = UUID.randomUUID();
        String sessionId = startStudy(token, taskId, requestId).path("id").asText();
        action(token, sessionId, "END", 0, "USER_STOPPED", 200);
        mvc.perform(delete(TASKS + "/" + taskId).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        JsonNode replay = request(post(STUDIES), token,
                "{\"clientRequestId\":\"" + requestId + "\",\"taskId\":\"" + taskId + "\"}", 200);
        assertEquals(sessionId, replay.path("id").asText());
        assertEquals("ENDED", replay.path("status").asText());
        assertTrue(replay.path("taskId").isNull());
    }

    @Test void authValidationErrorsAndPrivateResponsesFollowCommonContract() throws Exception {
        MvcResult missing = mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertError(missing, "AUTH_REQUIRED");
        assertEquals("no-store", missing.getResponse().getHeader("Cache-Control"));

        MvcResult invalid = mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer unknown-token"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertError(invalid, "AUTH_REQUIRED");

        String token = guest();
        MvcResult personal = mvc.perform(get(TASKS).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        assertEquals("no-store", personal.getResponse().getHeader("Cache-Control"));

        MvcResult malformed = mvc.perform(get(TASKS + "/not-a-uuid")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest()).andReturn();
        assertError(malformed, "VALIDATION_FAILED");

        clock.advanceSeconds(366L * 24 * 3600);
        MvcResult expired = mvc.perform(get("/api/v1/me")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized()).andReturn();
        assertError(expired, "AUTH_EXPIRED");
    }

    @Test void startContextPrefersActiveSessionThenSuggestsMostRecentUnfinishedTask() throws Exception {
        String token = guest();
        String first = createTask(token, "First", null, null).path("id").asText();
        String second = createTask(token, "Second", null, null).path("id").asText();
        String session = startStudy(token, first, UUID.randomUUID()).path("id").asText();
        clock.advanceSeconds(8);
        action(token, session, "END", 0, null, 200);
        clock.advanceSeconds(1);
        session = startStudy(token, first, UUID.randomUUID()).path("id").asText();
        clock.advanceSeconds(5);
        action(token, session, "PAUSE", 0, null, 200);
        clock.advanceSeconds(20);
        JsonNode active = startContext(token);
        assertEquals(session, active.path("currentSession").path("id").asText());
        assertEquals(5, active.path("currentSession").path("studySeconds").asLong());
        assertTrue(active.path("suggestedTask").isNull());
        action(token, session, "END", 1, null, 200);
        clock.advanceSeconds(1);
        session = startStudy(token, second, UUID.randomUUID()).path("id").asText();
        clock.advanceSeconds(2);
        action(token, session, "END", 0, null, 200);
        assertEquals(second, startContext(token).path("suggestedTask").path("taskId").asText());
        request(patch(TASKS + "/" + second), token, "{\"status\":\"DONE\"}", 200);

        JsonNode suggestion = startContext(token);
        assertTrue(suggestion.path("currentSession").isNull());
        assertEquals(first, suggestion.path("suggestedTask").path("taskId").asText());
        assertEquals(13, suggestion.path("suggestedTask").path("completedStudySeconds").asLong());
        assertTrue(startContext(guest()).path("suggestedTask").isNull());

        mvc.perform(delete(TASKS + "/" + first).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        assertTrue(startContext(token).path("suggestedTask").isNull());
    }

    private JsonNode startContext(String token) throws Exception {
        return json(mvc.perform(get(STUDIES + "/start-context").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private void assertError(MvcResult result, String expectedCode) throws Exception {
        JsonNode body = json(result);
        assertEquals(expectedCode, body.path("code").asText());
        String requestId = body.path("requestId").asText();
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(requestId, result.getResponse().getHeader("X-Request-ID"));
    }

    private MvcResult[] concurrentStarts(String token, UUID first, UUID second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<MvcResult> a = () -> concurrentStartRequest(token, first, ready, start);
            Callable<MvcResult> b = () -> concurrentStartRequest(token, second, ready, start);
            Future<MvcResult> f1 = pool.submit(a);
            Future<MvcResult> f2 = pool.submit(b);
            assertTrue(ready.await(5, TimeUnit.SECONDS), "both requests should be ready");
            start.countDown();
            return new MvcResult[]{f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS)};
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private MvcResult concurrentStartRequest(String token, UUID requestId, CountDownLatch ready,
                                             CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
        return mvc.perform(post(STUDIES).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"" + requestId + "\"}"))
                .andReturn();
    }

    private String guest() throws Exception {
        JsonNode issued = json(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn());
        assertTrue(issued.path("userId").isTextual());
        assertTrue(issued.path("expiresAt").isTextual());
        assertTrue(issued.path("accessToken").isTextual());
        return issued.path("accessToken").asText();
    }

    private JsonNode createTask(String token, String title, String description, String dueDate) throws Exception {
        StringBuilder body = new StringBuilder("{\"title\":\"").append(title).append('"');
        if (description != null) body.append(",\"description\":\"").append(description).append('"');
        if (dueDate != null) body.append(",\"dueDate\":\"").append(dueDate).append('"');
        return request(post(TASKS), token, body.append('}').toString(), 201);
    }

    private JsonNode startStudy(String token, String taskId, UUID key) throws Exception {
        String body = "{\"clientRequestId\":\"" + key + "\""
                + (taskId == null ? "" : ",\"taskId\":\"" + taskId + "\"") + "}";
        return request(post(STUDIES), token, body, 201);
    }

    private JsonNode action(String token, String sid, String action, int version, String reason, int expectedStatus) throws Exception {
        String body = "{\"action\":\"" + action + "\",\"expectedVersion\":" + version
                + (reason == null ? "" : ",\"endReason\":\"" + reason + "\"") + "}";
        return request(patch(STUDIES + "/" + sid), token, body, expectedStatus);
    }

    private JsonNode request(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
                             String token, String body, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(builder.header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn();
        return result.getResponse().getContentAsString().isEmpty()
                ? com.fasterxml.jackson.databind.node.NullNode.getInstance() : json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static long elapsed(JsonNode dto) {
        return dto.path("studySeconds").asLong();
    }

    @TestConfiguration static class ClockConfig {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(START); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        MutableClock(Instant instant) { this.instant = new AtomicReference<>(instant); }
        void set(Instant next) { instant.set(next); }
        void advanceSeconds(long seconds) { instant.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
