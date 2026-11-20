package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class TaskCreateIdempotencyIntegrationTest {
    private static final String TASKS = "/api/v1/tasks";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void taskRetryReturnsCurrentItemButCannotReviveDeletedResult() throws Exception {
        String token = guest();
        String requestId = UUID.randomUUID().toString();
        String body = "{\"title\":\"  발표 자료  \",\"description\":null,\"clientRequestId\":\"" + requestId + "\"}";
        JsonNode first = request(post(TASKS), token, body, 201);
        String id = first.path("id").asText();
        JsonNode replay = request(post(TASKS), token,
                "{\"title\":\"발표 자료\",\"clientRequestId\":\"" + requestId + "\"}", 200);
        assertEquals(id, replay.path("id").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE id=?", id));

        request(patch(TASKS + "/" + id), token, "{\"title\":\"수정된 자료\"}", 200);
        assertEquals("수정된 자료", request(post(TASKS), token, body, 200).path("title").asText());
        assertEquals("CLIENT_REQUEST_ID_REUSED", request(post(TASKS), token,
                "{\"title\":\"다른 자료\",\"clientRequestId\":\"" + requestId + "\"}", 409)
                .path("code").asText());

        request(delete(TASKS + "/" + id), token, null, 204);
        assertEquals("CREATE_RESULT_DELETED", request(post(TASKS), token, body, 409).path("code").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM tasks WHERE id=?", id));
        JsonNode withNewId = request(post(TASKS), token,
                "{\"title\":\"발표 자료\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}", 201);
        assertNotEquals(id, withNewId.path("id").asText());
        assertNotEquals(request(post(TASKS), token, "{\"title\":\"새 할 일\"}", 201).path("id").asText(),
                request(post(TASKS), token, "{\"title\":\"새 할 일\"}", 201).path("id").asText());
    }

    @Test void subtaskRequestIsScopedByUserAndParentAndRetainsDeletionTombstone() throws Exception {
        String owner = guest();
        String other = guest();
        String parent = request(post(TASKS), owner, "{\"title\":\"첫 할 일\"}", 201).path("id").asText();
        String otherParent = request(post(TASKS), owner, "{\"title\":\"둘째 할 일\"}", 201).path("id").asText();
        String otherUserParent = request(post(TASKS), other, "{\"title\":\"다른 사람 할 일\"}", 201)
                .path("id").asText();
        String requestId = UUID.randomUUID().toString();
        String body = "{\"title\":\"  첫 단계  \",\"clientRequestId\":\"" + requestId + "\"}";
        String url = TASKS + "/" + parent + "/subtasks";
        JsonNode first = request(post(url), owner, body, 201);
        String id = first.path("id").asText();
        assertEquals(id, request(post(url), owner,
                "{\"title\":\"첫 단계\",\"clientRequestId\":\"" + requestId + "\"}", 200)
                .path("id").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM subtasks WHERE task_id=?", parent));
        assertEquals("CLIENT_REQUEST_ID_REUSED", request(post(TASKS + "/" + otherParent + "/subtasks"),
                owner, body, 409).path("code").asText());
        assertEquals("CLIENT_REQUEST_ID_REUSED", request(post(TASKS), owner,
                "{\"title\":\"첫 단계\",\"clientRequestId\":\"" + requestId + "\"}", 409)
                .path("code").asText());
        assertEquals(201, rawStatus(post(TASKS + "/" + otherUserParent + "/subtasks"), other, body));
        request(delete(url + "/" + id), owner, null, 204);
        assertEquals("CREATE_RESULT_DELETED", request(post(url), owner, body, 409).path("code").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM subtasks WHERE task_id=?", parent));
    }

    @Test void simultaneousRetryMakesExactlyOneTaskAndOneSubtask() throws Exception {
        String token = guest();
        String taskRequest = UUID.randomUUID().toString();
        String taskBody = "{\"title\":\"동시 할 일\",\"clientRequestId\":\"" + taskRequest + "\"}";
        JsonNode[] taskResults = parallel(token, TASKS, taskBody);
        assertEquals(taskResults[0].path("id").asText(), taskResults[1].path("id").asText());
        String taskId = taskResults[0].path("id").asText();
        assertEquals(1, count("SELECT COUNT(*) FROM tasks WHERE id=?", taskId));
        String childBody = "{\"title\":\"동시 단계\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        JsonNode[] childResults = parallel(token, TASKS + "/" + taskId + "/subtasks", childBody);
        assertEquals(childResults[0].path("id").asText(), childResults[1].path("id").asText());
        assertEquals(1, count("SELECT COUNT(*) FROM subtasks WHERE task_id=?", taskId));
    }

    @Test void rejectsNoncanonicalRequestIdsBeforeWriting() throws Exception {
        String token = guest();
        request(post(TASKS), token, "{\"title\":\"x\",\"clientRequestId\":\"1-1-1-1-1\"}", 400);
        request(post(TASKS), token, "{\"title\":\"x\",\"clientRequestId\":null}", 400);
        String taskId = request(post(TASKS), token, "{\"title\":\"parent\"}", 201).path("id").asText();
        request(post(TASKS + "/" + taskId + "/subtasks"), token,
                "{\"title\":\"x\",\"clientRequestId\":\"1-1-1-1-1\"}", 400);
        assertEquals(0, count("SELECT COUNT(*) FROM subtasks WHERE task_id=?", taskId));
    }

    private JsonNode[] parallel(String token, String url, String body) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = pool.submit(() -> simultaneousRequest(token, url, body, ready, go));
            Future<MvcResult> second = pool.submit(() -> simultaneousRequest(token, url, body, ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            MvcResult a = first.get(15, TimeUnit.SECONDS);
            MvcResult b = second.get(15, TimeUnit.SECONDS);
            assertEquals(201, Math.max(a.getResponse().getStatus(), b.getResponse().getStatus()));
            assertEquals(200, Math.min(a.getResponse().getStatus(), b.getResponse().getStatus()));
            return new JsonNode[] {json(a), json(b)};
        } finally {
            go.countDown();
            pool.shutdownNow();
        }
    }

    private MvcResult simultaneousRequest(String token, String url, String body,
                                          CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        assertTrue(go.await(5, TimeUnit.SECONDS));
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private String guest() throws Exception {
        return json(mvc.perform(post("/api/v1/guest-sessions")).andExpect(status().isCreated()).andReturn())
                .path("accessToken").asText();
    }

    private int count(String sql, String id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    private int rawStatus(MockHttpServletRequestBuilder builder, String token, String body) throws Exception {
        return mvc.perform(builder.header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private JsonNode request(MockHttpServletRequestBuilder builder, String token, String body, int expected)
            throws Exception {
        builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(body);
        return json(mvc.perform(builder).andExpect(status().is(expected)).andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        String response = result.getResponse().getContentAsString();
        return response.isEmpty() ? null : mapper.readTree(response);
    }
}
