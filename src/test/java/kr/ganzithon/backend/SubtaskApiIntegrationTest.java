package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(BackendApiIntegrationTest.ClockConfig.class)
class SubtaskApiIntegrationTest {
    private static final String TASKS = "/api/v1/tasks";
    private static final Instant START = Instant.parse("2026-11-03T00:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BackendApiIntegrationTest.MutableClock clock;

    @BeforeEach void resetClock() { clock.set(START); }

    @Test void createCheckAndDeleteKeepSummaryAndDetailInSync() throws Exception {
        String token = guest();
        String taskId = createTask(token);
        JsonNode first = createSubtask(token, taskId, "  첫 단계  ");
        JsonNode second = createSubtask(token, taskId, "다음 단계");
        String firstId = first.path("id").asText();
        assertEquals("첫 단계", first.path("title").asText());
        assertEquals(taskId, first.path("taskId").asText());
        assertEquals(1, first.path("position").asInt());
        assertEquals(2, second.path("position").asInt());
        assertEquals("TODO", first.path("status").asText());
        assertTrue(first.path("completedAt").isNull());

        assertSummary(token, taskId, 2, 0, 0.0);
        JsonNode detail = getTask(token, taskId);
        assertEquals(firstId, detail.path("subtasks").get(0).path("id").asText());
        assertEquals(second.path("id").asText(), detail.path("subtasks").get(1).path("id").asText());

        String url = subtasks(taskId) + "/" + firstId;
        JsonNode done = request(patch(url), token, "{\"status\":\"DONE\"}", 200);
        String completedAt = done.path("completedAt").asText();
        assertTrue(done.path("completedAt").isTextual());
        clock.advanceSeconds(60);
        JsonNode repeated = request(patch(url), token, "{\"status\":\"DONE\",\"title\":\"  고친 단계  \"}", 200);
        assertEquals(completedAt, repeated.path("completedAt").asText());
        assertEquals("고친 단계", repeated.path("title").asText());
        assertSummary(token, taskId, 2, 1, 0.5);
        assertEquals("TODO", getTask(token, taskId).path("status").asText());

        request(patch(TASKS + "/" + taskId), token, "{\"status\":\"DONE\"}", 200);
        assertEquals("TODO", getTask(token, taskId).path("subtasks").get(1).path("status").asText());
        JsonNode reopened = request(patch(url), token, "{\"status\":\"TODO\"}", 200);
        assertTrue(reopened.path("completedAt").isNull());
        assertSummary(token, taskId, 2, 0, 0.0);

        request(delete(url), token, null, 204);
        assertEquals(2, getTask(token, taskId).path("subtasks").get(0).path("position").asInt());
        assertEquals(3, createSubtask(token, taskId, "세 번째").path("position").asInt());
        request(delete(subtasks(taskId) + "/" + second.path("id").asText()), token, null, 204);
        String thirdId = getTask(token, taskId).path("subtasks").get(0).path("id").asText();
        request(delete(subtasks(taskId) + "/" + thirdId), token, null, 204);
        JsonNode empty = getTask(token, taskId);
        assertEquals(0, empty.path("subtaskSummary").path("total").asInt());
        assertTrue(empty.path("subtaskSummary").path("ratio").isNull());
        assertEquals(0, empty.path("subtasks").size());
    }

    @Test void ownershipParentMatchAndCascadeAreEnforced() throws Exception {
        String owner = guest();
        String other = guest();
        String firstTask = createTask(owner);
        String secondTask = createTask(owner);
        String childId = createSubtask(owner, firstTask, "내 단계").path("id").asText();
        String childUrl = subtasks(firstTask) + "/" + childId;

        request(post(subtasks(firstTask)), other, "{\"title\":\"침범\"}", 404);
        request(patch(childUrl), other, "{\"status\":\"DONE\"}", 404);
        request(delete(childUrl), other, null, 404);
        request(patch(subtasks(secondTask) + "/" + childId), owner, "{\"status\":\"DONE\"}", 404);
        request(delete(subtasks(secondTask) + "/" + childId), owner, null, 404);
        request(delete(subtasks(firstTask) + "/" + UUID.randomUUID()), owner, null, 404);
        mvc.perform(post(subtasks(firstTask)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"무인증\"}")).andExpect(status().isUnauthorized());

        request(delete(TASKS + "/" + firstTask), owner, null, 204);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM subtasks WHERE id = ?",
                Integer.class, childId);
        assertEquals(0, count);
        request(patch(childUrl), owner, "{\"status\":\"DONE\"}", 404);
    }

    @Test void rejectsInvalidBodyAndUsesUnicodeCharacterLimit() throws Exception {
        String token = guest();
        String taskId = createTask(token);
        String url = subtasks(taskId);
        request(post(url), token, "{}", 400);
        request(post(url), token, "{\"title\":null}", 400);
        request(post(url), token, "{\"title\":\"   \"}", 400);
        request(post(url), token, "{\"title\":\"x\",\"status\":\"DONE\"}", 400);
        request(post(url), token, "{\"title\":\"" + "가".repeat(121) + "\"}", 400);
        JsonNode created = request(post(url), token, "{\"title\":\"  " + "가".repeat(120) + "  \"}", 201);
        assertEquals(120, created.path("title").asText().codePointCount(0, created.path("title").asText().length()));
        String childUrl = url + "/" + created.path("id").asText();
        request(patch(childUrl), token, "{}", 400);
        request(patch(childUrl), token, "{\"title\":null}", 400);
        request(patch(childUrl), token, "{\"status\":null}", 400);
        request(patch(childUrl), token, "{\"status\":\"PENDING\"}", 400);
        request(patch(childUrl), token, "{\"unexpected\":1}", 400);
        request(patch(url + "/not-a-uuid"), token, "{\"title\":\"x\"}", 400);
    }

    @Test void supplementaryUnicodeTitlesMatchCharacterLimits() throws Exception {
        String token = guest();
        String sheep = new String(Character.toChars(0x1F40F));
        String title = sheep.repeat(120);
        JsonNode task = request(post(TASKS), token, "{\"title\":\"" + title + "\"}", 201);
        String taskId = task.path("id").asText();
        assertEquals(title, task.path("title").asText());
        assertEquals(title, createSubtask(token, taskId, title).path("title").asText());
        request(post(TASKS), token, "{\"title\":\"" + sheep.repeat(121) + "\"}", 400);
        request(post(subtasks(taskId)), token, "{\"title\":\"" + sheep.repeat(121) + "\"}", 400);
    }

    @Test void parallelCreatesReceiveDistinctPositions() throws Exception {
        String token = guest();
        String taskId = createTask(token);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JsonNode> one = () -> concurrentCreate(token, taskId, "하나", ready, go);
            Callable<JsonNode> two = () -> concurrentCreate(token, taskId, "둘", ready, go);
            Future<JsonNode> first = pool.submit(one);
            Future<JsonNode> second = pool.submit(two);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            assertEquals(1, Math.min(first.get(10, TimeUnit.SECONDS).path("position").asInt(),
                    second.get(10, TimeUnit.SECONDS).path("position").asInt()));
            JsonNode detail = getTask(token, taskId);
            assertEquals(2, detail.path("subtasks").size());
            assertEquals(1, detail.path("subtasks").get(0).path("position").asInt());
            assertEquals(2, detail.path("subtasks").get(1).path("position").asInt());
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    private JsonNode concurrentCreate(String token, String taskId, String title,
                                      CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        if (!go.await(5, TimeUnit.SECONDS)) throw new AssertionError("start gate timed out");
        return request(post(subtasks(taskId)), token, "{\"title\":\"" + title + "\"}", 201);
    }

    private String guest() throws Exception {
        JsonNode body = json(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn());
        return body.path("accessToken").asText();
    }

    private String createTask(String token) throws Exception {
        return request(post(TASKS), token, "{\"title\":\"상위 할 일\"}", 201).path("id").asText();
    }

    private JsonNode createSubtask(String token, String taskId, String title) throws Exception {
        return request(post(subtasks(taskId)), token, "{\"title\":\"" + title + "\"}", 201);
    }

    private void assertSummary(String token, String taskId, int total, int done, double ratio) throws Exception {
        JsonNode detail = getTask(token, taskId);
        assertEquals(total, detail.path("subtaskSummary").path("total").asInt());
        assertEquals(done, detail.path("subtaskSummary").path("done").asInt());
        assertEquals(ratio, detail.path("subtaskSummary").path("ratio").asDouble());
        JsonNode list = json(mvc.perform(get(TASKS).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
        JsonNode row = null;
        for (JsonNode item : list.path("items")) {
            if (taskId.equals(item.path("id").asText())) row = item;
        }
        assertNotNull(row);
        assertEquals(total, row.path("subtaskSummary").path("total").asInt());
        assertEquals(done, row.path("subtaskSummary").path("done").asInt());
        assertEquals(ratio, row.path("subtaskSummary").path("ratio").asDouble());
    }

    private JsonNode getTask(String token, String taskId) throws Exception {
        return json(mvc.perform(get(TASKS + "/" + taskId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn());
    }

    private String subtasks(String taskId) { return TASKS + "/" + taskId + "/subtasks"; }

    private JsonNode request(MockHttpServletRequestBuilder builder, String token, String body,
                             int expectedStatus) throws Exception {
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result = mvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn();
        return result.getResponse().getContentAsString().isEmpty()
                ? null : json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
