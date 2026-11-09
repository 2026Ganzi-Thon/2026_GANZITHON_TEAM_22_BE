package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.UUID;
import kr.ganzithon.backend.common.WebConfiguration;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "ganzi.cors.allowed-origins=https://frontend.example.test,http://localhost:7891",
    "ganzi.demo.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class WebConfigurationIntegrationTest {
    private static final String ALLOWED = "https://frontend.example.test";
    private static final String DENIED = "https://unlisted.example.test";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void configuredOriginAllowsBearerPreflightWithoutCredentials() throws Exception {
        mvc.perform(options("/api/v1/tasks")
                .header("Origin", ALLOWED)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test void unlistedOriginIsRejectedForPreflightAndAuthenticatedRequest() throws Exception {
        mvc.perform(options("/api/v1/tasks")
                .header("Origin", DENIED)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        String token = guestToken();
        mvc.perform(get("/api/v1/tasks")
                .header("Origin", DENIED)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mvc.perform(get("/api/v1/tasks").header("Origin", DENIED))
            .andExpect(status().isForbidden());
    }

    @Test void allowedOriginStillNeedsBearerAndWorksWithBearer() throws Exception {
        String missing = mvc.perform(get("/api/v1/tasks").header("Origin", ALLOWED))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
            .andExpect(header().string("Access-Control-Expose-Headers",
                org.hamcrest.Matchers.containsString("Allow")))
            .andReturn().getResponse().getContentAsString();
        assertEquals("AUTH_REQUIRED", mapper.readTree(missing).get("code").asText());

        String unregistered = mvc.perform(get("/api/v1/tasks")
                .header("Origin", ALLOWED)
                .header("Authorization", "Bearer " + "A".repeat(43)))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
            .andReturn().getResponse().getContentAsString();
        assertEquals("AUTH_REQUIRED", mapper.readTree(unregistered).get("code").asText());

        String token = guestToken();
        mvc.perform(get("/api/v1/tasks")
                .header("Origin", ALLOWED)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test void expiredBearerKeepsCorsHeaderAndErrorCode() throws Exception {
        var issued = mapper.readTree(mvc.perform(post("/api/v1/guest-sessions"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        String token = issued.get("accessToken").asText();
        UUID userId = UUID.fromString(issued.get("userId").asText());
        jdbc.update("UPDATE auth_sessions SET expires_at=? WHERE user_id=?",
            LocalDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC), userId.toString());

        String expired = mvc.perform(get("/api/v1/tasks")
                .header("Origin", ALLOWED)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
            .andReturn().getResponse().getContentAsString();
        assertEquals("AUTH_EXPIRED", mapper.readTree(expired).get("code").asText());
    }

    @Test void malformedOriginListFailsFast() {
        for (String value : new String[] {
                "", "*", "http://localhost:7891,", "http://localhost:7891/path",
                "http://localhost:7891?x=1", "http://user@localhost:7891",
                "file://localhost", "http://*.example.test", "http://localhost:65536"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> new WebConfiguration(value), value);
        }
    }

    @Test void demoPathTraversalNeverOpensTheApi() throws Exception {
        mvc.perform(get("/demo/../api/v1/me"))
            .andExpect(status().is4xxClientError());
        mvc.perform(get("/demo/%2e%2e/api/v1/me"))
            .andExpect(status().is4xxClientError());
        mvc.perform(get("/demo/assets/.."))
            .andExpect(status().is4xxClientError());
        mvc.perform(post("/demo/index.html"))
            .andExpect(status().is4xxClientError());
    }

    private String guestToken() throws Exception {
        String json = mvc.perform(post("/api/v1/guest-sessions")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("accessToken").asText();
    }
}
