package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class TaskVersionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test void twoEditorsCanPreserveBothChangesAfterConflict() throws Exception {
        String token = guest();
        JsonNode created = json(mvc.perform(post("/api/v1/tasks")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"발표 초안\",\"description\":\"첫 설명\"}"))
                .andExpect(status().isCreated()).andReturn());
        String path = "/api/v1/tasks/" + created.path("id").asText();
        assertEquals(0, created.path("version").asInt());

        // Both editors opened the same initial version.
        JsonNode editorA = getTask(path, token);
        JsonNode editorB = getTask(path, token);
        assertEquals(0, editorA.path("version").asInt());
        assertEquals(0, editorB.path("version").asInt());

        JsonNode first = patchTask(path, token,
                "{\"title\":\"발표 최종본\",\"expectedVersion\":0}", 200);
        assertEquals(1, first.path("version").asInt());

        // The second editor's stale full-form payload must not overwrite A's title.
        JsonNode conflict = patchTask(path, token,
                "{\"title\":\"발표 초안\",\"description\":\"새 설명\",\"expectedVersion\":0}", 409);
        assertEquals("TASK_VERSION_CONFLICT", conflict.path("code").asText());
        assertEquals("발표 최종본", getTask(path, token).path("title").asText());

        // After refreshing, the editor sends only the field they changed.
        JsonNode second = patchTask(path, token,
                "{\"description\":\"새 설명\",\"expectedVersion\":1}", 200);
        assertEquals(2, second.path("version").asInt());
        JsonNode finalTask = getTask(path, token);
        assertEquals("발표 최종본", finalTask.path("title").asText());
        assertEquals("새 설명", finalTask.path("description").asText());
        assertEquals(2, finalTask.path("version").asInt());
        JsonNode list = json(mvc.perform(get("/api/v1/tasks")
                .header("Authorization", bearer(token))).andExpect(status().isOk()).andReturn());
        assertTrue(list.path("items").isArray());
        assertEquals(2, list.path("items").get(0).path("version").asInt());
    }

    @Test void noOpAndValidationAndLegacyPatchBehaveConsistently() throws Exception {
        String token = guest();
        JsonNode created = json(mvc.perform(post("/api/v1/tasks")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"같은 내용\"}"))
                .andExpect(status().isCreated()).andReturn());
        String path = "/api/v1/tasks/" + created.path("id").asText();
        String updatedAt = created.path("updatedAt").asText();

        JsonNode noOp = patchTask(path, token,
                "{\"title\":\"같은 내용\",\"expectedVersion\":0}", 200);
        assertEquals(0, noOp.path("version").asInt());
        assertEquals(updatedAt, noOp.path("updatedAt").asText());
        assertEquals("VALIDATION_FAILED", patchTask(path, token,
                "{\"expectedVersion\":0}", 400).path("code").asText());
        patchTask(path, token, "{\"title\":\"변경\",\"expectedVersion\":-1}", 400);
        patchTask(path, token, "{\"title\":\"변경\",\"expectedVersion\":0.5}", 400);
        patchTask(path, token, "{\"title\":\"변경\",\"expectedVersion\":null}", 400);

        JsonNode legacy = patchTask(path, token, "{\"title\":\"변경\"}", 200);
        assertEquals(1, legacy.path("version").asInt());
        assertEquals("변경", legacy.path("title").asText());
        JsonNode legacyNoOp = patchTask(path, token, "{\"title\":\"변경\"}", 200);
        assertEquals(1, legacyNoOp.path("version").asInt());
        assertEquals(legacy.path("updatedAt").asText(), legacyNoOp.path("updatedAt").asText());
    }

    private String guest() throws Exception {
        return json(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn()).path("accessToken").asText();
    }

    private JsonNode getTask(String path, String token) throws Exception {
        return json(mvc.perform(get(path).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode patchTask(String path, String token, String body, int expectedStatus) throws Exception {
        return json(mvc.perform(patch(path).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
