package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import kr.ganzithon.backend.identity.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "ganzi.push.enabled=false")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(BackendApiIntegrationTest.ClockConfig.class)
class StudyAwayDisabledApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BackendApiIntegrationTest.MutableClock clock;
    @Autowired IdentityService identities;
    private UUID ownerId;

    @AfterEach void cleanup() { if (ownerId != null) identities.deleteCurrentUser(ownerId); }

    @Test void tracksDepartureButReportsRemotePushDisabled() throws Exception {
        clock.set(Instant.parse("2026-11-03T00:00:00Z"));
        JsonNode issued=mapper.readTree(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        ownerId=UUID.fromString(issued.path("userId").asText());
        String auth=issued.path("accessToken").asText();
        String session=mapper.readTree(mvc.perform(post("/api/v1/study-sessions")
                .header("Authorization","Bearer "+auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientRequestId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .path("id").asText();
        String installation=UUID.randomUUID().toString();
        mvc.perform(put("/api/v1/push-devices/"+installation)
                .header("Authorization","Bearer "+auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expoPushToken\":\"ExpoPushToken["+UUID.randomUUID().toString().replace("-","")+ "]\"}"))
                .andExpect(status().isOk());
        JsonNode presence=mapper.readTree(mvc.perform(put("/api/v1/study-sessions/"+session+"/presence")
                .header("Authorization","Bearer "+auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"installationId\":\""+installation+"\",\"eventId\":\""+UUID.randomUUID()
                        +"\",\"sequence\":1,\"state\":\"BACKGROUND\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(presence.path("deliveryEnabled").asBoolean(true));
        assertEquals("PENDING",presence.path("deliveryStatus").asText());
        assertEquals("2026-11-03T00:05:00Z",presence.path("dueAt").asText());
    }
}
