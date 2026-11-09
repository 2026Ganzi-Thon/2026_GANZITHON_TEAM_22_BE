package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.identity.IdentityService;
import kr.ganzithon.backend.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class IdentityDeletionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identities;
    @Autowired TaskService tasks;

    @Test
    void deletionRemovesTheOwnersActiveRecordsAndAllCredentialsButPreservesAnotherUser() throws Exception {
        Guest owner = guest();
        Guest other = guest();
        JsonNode task = postJson("/api/v1/tasks", owner.token(), "{\"title\":\"삭제할 할 일\"}");
        String taskId = task.path("id").asText();
        JsonNode subtask = postJson("/api/v1/tasks/" + taskId + "/subtasks",
                owner.token(), "{\"title\":\"삭제할 세부 항목\"}");
        JsonNode study = postJson("/api/v1/study-sessions", owner.token(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"taskId\":\"" + taskId + "\"}");
        JsonNode otherTask = postJson("/api/v1/tasks", other.token(), "{\"title\":\"남겨 둘 할 일\"}");
        String otherTaskId = otherTask.path("id").asText();
        JsonNode otherSubtask = postJson("/api/v1/tasks/" + otherTaskId + "/subtasks",
                other.token(), "{\"title\":\"남겨 둘 세부 항목\"}");
        JsonNode otherStudy = postJson("/api/v1/study-sessions", other.token(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"taskId\":\"" + otherTaskId + "\"}");

        // A second synthetic authentication row proves deletion is owner-wide.
        jdbc.update("""
                INSERT INTO auth_sessions(id, user_id, secret_hash, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), owner.id().toString(), "b".repeat(64),
                LocalDateTime.ofInstant(Instant.now().plusSeconds(3600), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));

        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(owner.token())))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));

        for (String table : new String[]{"tasks", "study_sessions", "auth_sessions"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
                    Integer.class, owner.id().toString()));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, owner.id().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subtasks WHERE id = ?",
                Integer.class, subtask.path("id").asText()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM study_sessions WHERE id = ?",
                Integer.class, study.path("id").asText()));

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(owner.token())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(owner.token())))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/me").header("Authorization", bearer(other.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(other.id().toString()));
        mvc.perform(get("/api/v1/tasks/" + otherTaskId).header("Authorization", bearer(other.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtasks[0].id").value(otherSubtask.path("id").asText()));
        mvc.perform(get("/api/v1/study-sessions/" + otherStudy.path("id").asText())
                        .header("Authorization", bearer(other.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STUDYING"));
    }

    @Test
    void missingOrExpiredAuthenticationCannotDeleteData() throws Exception {
        mvc.perform(delete("/api/v1/me"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        Guest owner = guest();
        jdbc.update("UPDATE auth_sessions SET expires_at = ? WHERE user_id = ?",
                LocalDateTime.ofInstant(Instant.EPOCH, ZoneOffset.UTC), owner.id().toString());
        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(owner.token())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_EXPIRED"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, owner.id().toString()));
    }

    @Test
    void aBodyCannotSelectSomeoneElseOrSilentlyChangeDeletionMeaning() throws Exception {
        Guest owner = guest();
        Guest other = guest();
        mvc.perform(delete("/api/v1/me").header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + other.id() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        for (Guest guest : new Guest[]{owner, other}) {
            mvc.perform(get("/api/v1/me").header("Authorization", bearer(guest.token())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void failureOfTheFinalDeleteRollsBackPreviouslyRemovedData() throws Exception {
        Guest owner = guest();
        JsonNode task = postJson("/api/v1/tasks", owner.token(), "{\"title\":\"롤백 확인\"}");
        JsonNode study = postJson("/api/v1/study-sessions", owner.token(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\"}");
        jdbc.execute("CREATE TABLE IF NOT EXISTS deletion_test_guard("
                + "user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, "
                + "CONSTRAINT fk_deletion_test_guard_user FOREIGN KEY(user_id) REFERENCES users(id)) ENGINE=InnoDB");
        try {
            jdbc.update("INSERT INTO deletion_test_guard(user_id) VALUES (?)", owner.id().toString());
            mvc.perform(delete("/api/v1/me").header("Authorization", bearer(owner.token())))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
            mvc.perform(get("/api/v1/me").header("Authorization", bearer(owner.token())))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/tasks/" + task.path("id").asText())
                            .header("Authorization", bearer(owner.token())))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/study-sessions/" + study.path("id").asText())
                            .header("Authorization", bearer(owner.token())))
                    .andExpect(status().isOk());
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS deletion_test_guard");
        }
    }

    @Test
    void alreadyAuthenticatedLateWritesCannotRecreateADeletedOwner() throws Exception {
        Guest owner = guest();
        identities.deleteCurrentUser(owner.id());
        ApiException failure = assertThrows(ApiException.class,
                () -> tasks.create(owner.id(), mapper.readTree("{\"title\":\"늦은 요청\"}")));
        assertEquals(401, failure.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE user_id = ?",
                Integer.class, owner.id().toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, owner.id().toString()));
    }

    private Guest guest() throws Exception {
        JsonNode body = json(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn());
        return new Guest(UUID.fromString(body.path("userId").asText()), body.path("accessToken").asText());
    }

    private JsonNode postJson(String path, String token, String body) throws Exception {
        return json(mvc.perform(post(path).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) { return "Bearer " + token; }
    private record Guest(UUID id, String token) {}
}
