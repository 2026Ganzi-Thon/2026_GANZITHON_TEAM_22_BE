package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class EmailAuthIntegrationTest {
    private static final String PASSWORD="correct-password-가나다라🔐";
    private static final String NEW_PASSWORD="replacement-secret-한글🔒";
    private final String emailNamespace = UUID.randomUUID().toString().replace("-", "");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void guestConvertsWithoutLosingTasksAndOldSessionIsRevoked() throws Exception {
        JsonNode guest=guest();
        String oldToken=guest.get("accessToken").asText();
        String userId=guest.get("userId").asText();
        mvc.perform(post("/api/v1/tasks").header("Authorization",bearer(oldToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"남겨 둘 할 일\"}"))
            .andExpect(status().isCreated());

        JsonNode linked=register(email("  STUDENT@example.com  "),PASSWORD,oldToken);
        assertEquals(userId,linked.get("userId").asText());
        assertEquals("EMAIL",linked.get("accountType").asText());
        assertEquals(email("student@example.com"),linked.get("email").asText());
        assertFalse(linked.get("emailVerified").asBoolean());
        mvc.perform(get("/api/v1/me").header("Authorization",bearer(oldToken)))
            .andExpect(status().isUnauthorized());

        String newToken=linked.get("accessToken").asText();
        JsonNode me=json(mvc.perform(get("/api/v1/me").header("Authorization",bearer(newToken)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("EMAIL",me.get("accountType").asText());
        assertEquals(email("student@example.com"),me.get("email").asText());
        assertFalse(me.has("accessToken"));
        JsonNode tasks=json(mvc.perform(get("/api/v1/tasks").header("Authorization",bearer(newToken)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,tasks.get("items").size());
        assertEquals("남겨 둘 할 일",tasks.get("items").get(0).get("title").asText());

        String stored=jdbc.queryForObject("SELECT password_hash FROM email_accounts WHERE user_id=?",
            String.class,userId);
        assertTrue(stored.startsWith("pbkdf2-sha256$600000$"));
        assertFalse(stored.contains(PASSWORD));
    }

    @Test void normalizedDuplicateAndUnknownCredentialsDoNotExposeAccounts() throws Exception {
        register(email("CASE@example.org"),PASSWORD,null);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email(" case@EXAMPLE.org "),"password",PASSWORD))))
            .andExpect(status().isConflict());

        JsonNode wrong=loginError(email("case@example.org"),"wrong-password-value");
        JsonNode missing=loginError(email("absent@example.org"),"wrong-password-value");
        assertEquals("INVALID_CREDENTIALS",wrong.get("code").asText());
        assertEquals(wrong.get("code").asText(),missing.get("code").asText());
        assertEquals(wrong.get("message").asText(),missing.get("message").asText());
        assertFalse(wrong.toString().contains(email("case@example.org")));

        JsonNode login=login(email("case@example.org"),PASSWORD);
        assertEquals("EMAIL",login.get("accountType").asText());
        assertFalse(login.get("emailVerified").asBoolean());
    }

    @Test void duplicateEmailDoesNotConsumeGuestOrMergeItsRecords() throws Exception {
        JsonNode existing=register(email("owner@example.org"),PASSWORD,null);
        JsonNode guest=guest();
        String guestToken=guest.get("accessToken").asText();
        mvc.perform(post("/api/v1/tasks").header("Authorization",bearer(guestToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"게스트만의 기록\"}"))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/register")
                .header("Authorization",bearer(guestToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email("OWNER@example.org"),"password",PASSWORD))))
            .andExpect(status().isConflict());
        JsonNode guestTasks=json(mvc.perform(get("/api/v1/tasks")
                .header("Authorization",bearer(guestToken)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,guestTasks.get("items").size());
        assertNotEquals(existing.get("userId").asText(),guest.get("userId").asText());
        JsonNode ownerTasks=json(mvc.perform(get("/api/v1/tasks")
                .header("Authorization",bearer(existing.get("accessToken").asText())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0,ownerTasks.get("items").size());
    }

    @Test void logoutAndPasswordChangeRevokeSessions() throws Exception {
        JsonNode registered=register(email("rotate@example.org"),PASSWORD,null);
        String original=registered.get("accessToken").asText();
        String other=login(email("rotate@example.org"),PASSWORD).get("accessToken").asText();

        mvc.perform(post("/api/v1/auth/logout").header("Authorization",bearer(original)))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization",bearer(original)))
            .andExpect(status().isUnauthorized());

        JsonNode rejected=json(mvc.perform(post("/api/v1/auth/password")
                .header("Authorization",bearer(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("currentPassword","wrong-current-password",
                    "newPassword",NEW_PASSWORD))))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString());
        assertEquals("INVALID_CREDENTIALS",rejected.get("code").asText());
        mvc.perform(get("/api/v1/me").header("Authorization",bearer(other)))
            .andExpect(status().isOk());

        JsonNode changed=json(mvc.perform(post("/api/v1/auth/password")
                .header("Authorization",bearer(other))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("currentPassword",PASSWORD,"newPassword",NEW_PASSWORD))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String latest=changed.get("accessToken").asText();
        assertNotEquals(other,latest);
        mvc.perform(get("/api/v1/me").header("Authorization",bearer(other)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization",bearer(latest)))
            .andExpect(status().isOk());
        assertEquals("INVALID_CREDENTIALS",loginError(email("rotate@example.org"),PASSWORD).get("code").asText());
        assertEquals("EMAIL",login(email("rotate@example.org"),NEW_PASSWORD).get("accountType").asText());
    }

    @Test void deleteEmailAccountAllowsFreshRegistrationWithoutOldRecords() throws Exception {
        JsonNode first=register(email("again@example.org"),PASSWORD,null);
        String firstToken=first.get("accessToken").asText();
        mvc.perform(post("/api/v1/tasks").header("Authorization",bearer(firstToken))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"이전 기록\"}"))
            .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/me").header("Authorization",bearer(firstToken)))
            .andExpect(status().isNoContent());
        assertEquals("INVALID_CREDENTIALS",loginError(email("again@example.org"),PASSWORD).get("code").asText());

        JsonNode fresh=register(email("again@example.org"),PASSWORD,null);
        assertNotEquals(first.get("userId").asText(),fresh.get("userId").asText());
        JsonNode tasks=json(mvc.perform(get("/api/v1/tasks")
                .header("Authorization",bearer(fresh.get("accessToken").asText())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0,tasks.get("items").size());
    }

    @Test void guestProfileHasNoEmailAndWeakPasswordIsRejected() throws Exception {
        JsonNode guest=guest();
        JsonNode me=json(mvc.perform(get("/api/v1/me")
                .header("Authorization",bearer(guest.get("accessToken").asText())))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("GUEST",me.get("accountType").asText());
        assertTrue(me.get("email").isNull());
        assertFalse(me.get("emailVerified").asBoolean());
        assertFalse(guest.has("email"));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email("weak@example.org"),"password","short"))))
            .andExpect(status().isBadRequest());
    }

    @Test void demoIsHiddenByDefaultButApiStillRequiresBearer() throws Exception {
        mvc.perform(get("/demo/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    private JsonNode guest() throws Exception {
        return json(mvc.perform(post("/api/v1/guest-sessions"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode register(String email,String password,String bearer) throws Exception {
        var request=post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(Map.of("email",email,"password",password)));
        if (bearer!=null) request.header("Authorization",bearer(bearer));
        return json(mvc.perform(request).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
    }
    private JsonNode login(String email,String password) throws Exception {
        return json(mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email,"password",password))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode loginError(String email,String password) throws Exception {
        String body=mvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email,"password",password))))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        return json(body);
    }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    // Tests can be rerun against the same MySQL schema without colliding with earlier synthetic accounts.
    private String email(String fixture) { return fixture.replace("@", "+" + emailNamespace + "@"); }
    private String bearer(String token) { return "Bearer "+token; }
}
