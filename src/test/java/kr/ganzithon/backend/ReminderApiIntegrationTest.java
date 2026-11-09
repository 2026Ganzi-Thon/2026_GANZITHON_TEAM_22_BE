package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(BackendApiIntegrationTest.ClockConfig.class)
class ReminderApiIntegrationTest {
    private static final String STUDIES = "/api/v1/study-sessions";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BackendApiIntegrationTest.MutableClock clock;

    @BeforeEach void reset() { clock.set(Instant.parse("2026-11-03T00:00:00Z")); }

    @Test void reminderLifecycleAndIdempotency() throws Exception {
        String owner = guest();
        String other = guest();
        String sid = study(owner);
        end(owner, sid);
        String path = STUDIES + "/" + sid + "/next-start-reminder";
        String input = "{\"remindAt\":\"2026-11-04T09:00:00.123456789+09:00\",\"timeZone\":\"Asia/Seoul\"}";
        JsonNode created = putReminder(owner, path, input, 201);
        String rid = created.path("reminder").path("id").asText();
        assertEquals("ACTIVE", created.path("reminder").path("status").asText());
        assertEquals("2026-11-04T00:00:00.123456Z", created.path("reminder").path("remindAt").asText());
        assertTrue(created.path("serverTime").isTextual());
        assertEquals(rid, putReminder(owner, path, input, 200).path("reminder").path("id").asText());
        assertEquals("REMINDER_REQUEST_CONFLICT", putReminder(owner, path,
                "{\"remindAt\":\"2026-11-05T00:00:00Z\",\"timeZone\":\"Asia/Seoul\"}", 409)
                .path("code").asText());
        assertEquals(rid, authorized(get("/api/v1/start-reminders/current"), owner, null, 200)
                .path("reminder").path("id").asText());
        authorized(delete("/api/v1/start-reminders/" + rid), other, null, 404);
        authorized(delete("/api/v1/start-reminders/" + rid), owner, null, 204);
        authorized(delete("/api/v1/start-reminders/" + rid), owner, null, 204);
        assertTrue(authorized(get("/api/v1/start-reminders/current"), owner, null, 200)
                .path("reminder").isNull());
        assertEquals("CANCELED", putReminder(owner, path, input, 200).path("reminder").path("status").asText());
    }

    @Test void latestEndedSourceAndExpiry() throws Exception {
        String token = guest();
        String first = study(token);
        end(token, first);
        clock.advanceSeconds(1);
        String second = study(token);
        String firstPath = STUDIES + "/" + first + "/next-start-reminder";
        String secondPath = STUDIES + "/" + second + "/next-start-reminder";
        String input = "{\"remindAt\":\"2026-11-04T00:00:00Z\",\"timeZone\":\"Asia/Seoul\"}";
        assertEquals("REMINDER_SOURCE_NOT_LATEST", putReminder(token, firstPath, input, 409)
                .path("code").asText());
        assertEquals("STUDY_NOT_ENDED", putReminder(token, secondPath, input, 409)
                .path("code").asText());
        end(token, second);
        assertEquals("ACTIVE", putReminder(token, secondPath, input, 201).path("reminder").path("status").asText());
        clock.advanceSeconds(24 * 3600);
        assertTrue(authorized(get("/api/v1/start-reminders/current"), token, null, 200)
                .path("reminder").isNull());
        assertEquals("EXPIRED", putReminder(token, secondPath, input, 200).path("reminder").path("status").asText());
    }

    @Test void genuinelyNewStudyCancelsButReplayDoesNot() throws Exception {
        String token = guest();
        String first = study(token);
        end(token, first);
        String path = STUDIES + "/" + first + "/next-start-reminder";
        String input = "{\"remindAt\":\"2026-11-04T00:00:00Z\",\"timeZone\":\"Asia/Seoul\"}";
        putReminder(token, path, input, 201);
        String requestId = UUID.randomUUID().toString();
        String startBody = "{\"clientRequestId\":\"" + requestId + "\"}";
        authorized(post(STUDIES), token, startBody, 201);
        assertEquals("CANCELED", putReminder(token, path, input, 200).path("reminder").path("status").asText());
        assertEquals("NEW_STUDY", putReminder(token, path, input, 200)
                .path("reminder").path("canceledReason").asText());
        authorized(post(STUDIES), token, startBody, 200);
        assertTrue(authorized(get("/api/v1/start-reminders/current"), token, null, 200)
                .path("reminder").isNull());
    }

    @Test void activeReminderTimeCanChangeWithObservedValueOnly() throws Exception {
        String owner = guest();
        String other = guest();
        String sid = study(owner);
        end(owner, sid);
        String createPath = STUDIES + "/" + sid + "/next-start-reminder";
        String initial = "{\"remindAt\":\"2026-11-04T00:00:00Z\",\"timeZone\":\"Asia/Seoul\"}";
        String rid = putReminder(owner, createPath, initial, 201).path("reminder").path("id").asText();
        String patchPath = "/api/v1/start-reminders/" + rid;
        String stale = "{\"expectedRemindAt\":\"2026-11-06T00:00:00Z\","
                + "\"expectedTimeZone\":\"Asia/Seoul\",\"remindAt\":\"2026-11-05T00:00:00Z\","
                + "\"timeZone\":\"Asia/Seoul\"}";
        assertEquals("REMINDER_PRECONDITION_FAILED", authorized(patch(patchPath), owner, stale, 409)
                .path("code").asText());
        String change = stale.replace("2026-11-06", "2026-11-04");
        authorized(patch(patchPath), other, change, 404);
        JsonNode updated = authorized(patch(patchPath), owner, change, 200);
        assertEquals(rid, updated.path("reminder").path("id").asText());
        assertEquals("2026-11-05T00:00:00Z", updated.path("reminder").path("remindAt").asText());
        assertEquals(rid, authorized(patch(patchPath), owner, change, 200)
                .path("reminder").path("id").asText());
        assertEquals("REMINDER_REQUEST_CONFLICT", putReminder(owner, createPath, initial, 409)
                .path("code").asText());
        authorized(delete(patchPath), owner, null, 204);
        assertEquals("REMINDER_NOT_ACTIVE", authorized(patch(patchPath), owner, change, 409)
                .path("code").asText());
    }

    @Test void taskCandidateDatesAreLocalAndPaginated() throws Exception {
        String token = guest();
        authorized(post("/api/v1/tasks"), token, "{\"title\":\"tomorrow\",\"dueDate\":\"2026-11-04\"}", 201);
        authorized(post("/api/v1/tasks"), token, "{\"title\":\"later\",\"dueDate\":\"2026-11-05\"}", 201);
        authorized(post("/api/v1/tasks"), token, "{\"title\":\"past\",\"dueDate\":\"2026-11-03\"}", 201);
        JsonNode page = authorized(get("/api/v1/task-reminders?timeZone=Asia/Seoul&limit=1"), token, null, 200);
        assertEquals(1, page.path("items").size());
        assertEquals("2026-11-04", page.path("items").get(0).path("dueDate").asText());
        assertEquals("2026-11-03", page.path("items").get(0).path("reminderDate").asText());
        assertEquals(1, page.path("nextOffset").asInt());
        JsonNode next = authorized(get("/api/v1/task-reminders?timeZone=Asia/Seoul&limit=1&offset=1"), token, null, 200);
        assertEquals("2026-11-05", next.path("items").get(0).path("dueDate").asText());
        assertTrue(next.path("nextOffset").isNull());
    }

    private String guest() throws Exception {
        return authorized(post("/api/v1/guest-sessions"), null, null, 201).path("accessToken").asText();
    }

    private String study(String token) throws Exception {
        return authorized(post(STUDIES), token,
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\"}", 201).path("id").asText();
    }

    private void end(String token, String sid) throws Exception {
        authorized(patch(STUDIES + "/" + sid), token,
                "{\"action\":\"END\",\"expectedVersion\":0}", 200);
    }

    private JsonNode putReminder(String token, String path, String body, int status) throws Exception {
        return authorized(put(path), token, body, status);
    }

    private JsonNode authorized(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                String token, String body, int expectedStatus) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? mapper.getNodeFactory().nullNode() : mapper.readTree(content);
    }
}
