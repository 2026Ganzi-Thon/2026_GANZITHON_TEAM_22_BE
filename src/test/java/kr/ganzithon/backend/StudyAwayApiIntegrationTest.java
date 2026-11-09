package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ganzithon.backend.notification.PushSender;
import kr.ganzithon.backend.notification.StudyAwayDispatcher;
import kr.ganzithon.backend.identity.IdentityService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"ganzi.push.enabled=true", "ganzi.push.dispatch-interval-ms=3600000"})
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import({BackendApiIntegrationTest.ClockConfig.class, StudyAwayApiIntegrationTest.FakePushConfig.class})
class StudyAwayApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BackendApiIntegrationTest.MutableClock clock;
    @Autowired StudyAwayDispatcher dispatcher;
    @Autowired FakePushSender sender;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identities;
    private final List<UUID> owners = new ArrayList<>();

    @AfterEach void cleanup() { owners.forEach(identities::deleteCurrentUser); }

    @BeforeEach void reset() {
        clock.set(Instant.parse("2026-11-03T00:00:00Z"));
        sender.calls.set(0);
    }

    @Test void departureWaitsFiveMinutesAndOnlySendsOnce() throws Exception {
        String token = guest();
        String sid = study(token);
        String device = register(token);
        String path = "/api/v1/study-sessions/"+sid+"/presence";
        JsonNode first = presence(token,path,device,UUID.randomUUID().toString(),1,"BACKGROUND",200);
        assertTrue(first.path("deliveryEnabled").asBoolean());
        assertEquals("2026-11-03T00:05:00Z",first.path("dueAt").asText());
        clock.advanceSeconds(180);
        presence(token,path,device,UUID.randomUUID().toString(),2,"BACKGROUND",200);
        dispatcher.dispatch();
        assertEquals(0,sender.calls.get());
        clock.advanceSeconds(120);
        dispatcher.dispatch();
        dispatcher.dispatch();
        assertEquals(1,sender.calls.get());
    }

    @Test void foregroundAndPauseCancelPendingWhileStudyContinuesUntilEnd() throws Exception {
        String token=guest();
        String sid=study(token);
        String device=register(token);
        String path="/api/v1/study-sessions/"+sid+"/presence";
        presence(token,path,device,UUID.randomUUID().toString(),1,"BACKGROUND",200);
        clock.advanceSeconds(240);
        JsonNode returned=presence(token,path,device,UUID.randomUUID().toString(),2,"FOREGROUND",200);
        assertEquals("CANCELED",returned.path("deliveryStatus").asText());
        assertEquals("STUDYING",authorized(get("/api/v1/study-sessions/"+sid),token,null,200)
                .path("status").asText());
        clock.advanceSeconds(120);
        dispatcher.dispatch();
        assertEquals(0,sender.calls.get());
        presence(token,path,device,UUID.randomUUID().toString(),3,"BACKGROUND",200);
        authorized(patch("/api/v1/study-sessions/"+sid),token,
                "{\"action\":\"PAUSE\",\"expectedVersion\":0}",200);
        clock.advanceSeconds(360);
        dispatcher.dispatch();
        assertEquals(0,sender.calls.get());
    }

    @Test void rejectsOtherUsersDeviceAndOldSequence() throws Exception {
        String owner=guest(), other=guest();
        String sid=study(owner);
        String device=register(owner);
        String path="/api/v1/study-sessions/"+sid+"/presence";
        String event=UUID.randomUUID().toString();
        assertEquals("RESOURCE_NOT_FOUND",presence(other,path,device,event,1,"BACKGROUND",404).path("code").asText());
        JsonNode first=presence(owner,path,device,event,1,"BACKGROUND",200);
        assertEquals(first.path("dueAt"),presence(owner,path,device,event,1,"BACKGROUND",200).path("dueAt"));
        assertEquals("PRESENCE_SEQUENCE_CONFLICT",presence(owner,path,device,UUID.randomUUID().toString(),1,
                "FOREGROUND",409).path("code").asText());
        authorized(put("/api/v1/push-devices/"+device),other,
                "{\"expoPushToken\":\"ExpoPushToken[AnotherToken123]\"}",409);
    }

    @Test void sameOwnerCanMoveUnchangedExpoTokenToReinstalledApp() throws Exception {
        String owner=guest(), other=guest();
        String sid=study(owner);
        String oldInstallation=UUID.randomUUID().toString();
        String newInstallation=UUID.randomUUID().toString();
        String expoToken="ExpoPushToken["+UUID.randomUUID().toString().replace("-","")+"]";
        String tokenBody="{\"expoPushToken\":\""+expoToken+"\"}";
        authorized(put("/api/v1/push-devices/"+oldInstallation),owner,tokenBody,200);
        presence(owner,"/api/v1/study-sessions/"+sid+"/presence",oldInstallation,
                UUID.randomUUID().toString(),1,"BACKGROUND",200);
        assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM study_app_presence WHERE installation_id=?",
                Long.class,oldInstallation).longValue());
        authorized(put("/api/v1/push-devices/"+newInstallation),owner,tokenBody,200);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM study_app_presence WHERE installation_id=?",
                Long.class,oldInstallation).longValue());
        assertEquals("RESOURCE_NOT_FOUND",presence(owner,"/api/v1/study-sessions/"+sid+"/presence",
                oldInstallation,UUID.randomUUID().toString(),2,"BACKGROUND",404).path("code").asText());
        presence(owner,"/api/v1/study-sessions/"+sid+"/presence",newInstallation,
                UUID.randomUUID().toString(),1,"BACKGROUND",200);
        assertEquals("PUSH_DEVICE_OWNERSHIP_CONFLICT",authorized(
                put("/api/v1/push-devices/"+newInstallation),other,tokenBody,409).path("code").asText());
        assertEquals("PUSH_TOKEN_OWNERSHIP_CONFLICT",authorized(
                put("/api/v1/push-devices/"+UUID.randomUUID()),other,tokenBody,409).path("code").asText());
    }

    @Test void resumedStudyRearmsAfterMissedForegroundWithoutResettingRepeatedBackground() throws Exception {
        String token=guest();
        String sid=study(token);
        String device=register(token);
        String path="/api/v1/study-sessions/"+sid+"/presence";
        presence(token,path,device,UUID.randomUUID().toString(),1,"BACKGROUND",200);
        authorized(patch("/api/v1/study-sessions/"+sid),token,
                "{\"action\":\"PAUSE\",\"expectedVersion\":0}",200);
        authorized(patch("/api/v1/study-sessions/"+sid),token,
                "{\"action\":\"RESUME\",\"expectedVersion\":1}",200);
        clock.advanceSeconds(60);
        JsonNode rearmed=presence(token,path,device,UUID.randomUUID().toString(),2,"BACKGROUND",200);
        assertEquals("PENDING",rearmed.path("deliveryStatus").asText());
        assertEquals("2026-11-03T00:06:00Z",rearmed.path("dueAt").asText());
        clock.advanceSeconds(180);
        JsonNode repeated=presence(token,path,device,UUID.randomUUID().toString(),3,"BACKGROUND",200);
        assertEquals(rearmed.path("dueAt"),repeated.path("dueAt"));
        dispatcher.dispatch();
        assertEquals(0,sender.calls.get());
        clock.advanceSeconds(120);
        dispatcher.dispatch();
        assertEquals(1,sender.calls.get());
    }

    private String guest() throws Exception {
        JsonNode issued = authorized(post("/api/v1/guest-sessions"),null,null,201);
        owners.add(UUID.fromString(issued.path("userId").asText()));
        return issued.path("accessToken").asText();
    }
    private String study(String token) throws Exception {
        return authorized(post("/api/v1/study-sessions"),token,
                "{\"clientRequestId\":\""+UUID.randomUUID()+"\"}",201).path("id").asText();
    }
    private String register(String token) throws Exception {
        String device=UUID.randomUUID().toString();
        authorized(put("/api/v1/push-devices/"+device),token,
                "{\"expoPushToken\":\"ExpoPushToken["+UUID.randomUUID().toString().replace("-","")+ "]\"}",200);
        return device;
    }
    private JsonNode presence(String token,String path,String device,String event,long seq,String state,int expected)
            throws Exception {
        return authorized(put(path),token,"{\"installationId\":\""+device+"\",\"eventId\":\""+event
                +"\",\"sequence\":"+seq+",\"state\":\""+state+"\"}",expected);
    }
    private JsonNode authorized(MockHttpServletRequestBuilder req,String token,String body,int expected) throws Exception {
        if(token!=null) req.header("Authorization","Bearer "+token);
        if(body!=null) req.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult result=mvc.perform(req).andExpect(status().is(expected)).andReturn();
        String content=result.getResponse().getContentAsString();
        return content.isEmpty()?mapper.getNodeFactory().nullNode():mapper.readTree(content);
    }

    @TestConfiguration static class FakePushConfig {
        @Bean @Primary FakePushSender fakePushSender() { return new FakePushSender(); }
    }
    static class FakePushSender implements PushSender {
        final AtomicInteger calls=new AtomicInteger();
        @Override public Result send(String expoPushToken,UUID sessionId) {
            calls.incrementAndGet();
            return Result.sent(UUID.randomUUID().toString());
        }
    }
}
