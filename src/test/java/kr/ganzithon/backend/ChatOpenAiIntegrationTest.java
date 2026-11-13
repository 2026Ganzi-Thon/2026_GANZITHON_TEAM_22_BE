package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.chat.ChatProvider;
import kr.ganzithon.backend.chat.ChatProviderException;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real HTTP and durable budget path without contacting a paid provider. */
@SpringBootTest(properties = "ganzi.chat.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(ChatOpenAiIntegrationTest.FakeProviderConfig.class)
class ChatOpenAiIntegrationTest {
    private static final String SCOPE = "openai_route_"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    @DynamicPropertySource
    static void budgetProperties(DynamicPropertyRegistry properties) {
        properties.add("ganzi.chat.budget.scope", () -> SCOPE);
        properties.add("ganzi.chat.budget.user-daily-requests", () -> "2");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdentityService identities;
    @Autowired FakeMeteredProvider provider;
    private final List<UUID> users = new ArrayList<>();

    @BeforeEach
    void resetProvider() {
        provider.calls.set(0);
        provider.observedStudyState.set(null);
        provider.quotedTurn.set(null);
        provider.generatedTurn.set(null);
    }

    @AfterEach
    void cleanOnlyThisScopeAndUsers() {
        for (UUID user : users) identities.deleteCurrentUser(user);
        jdbc.update("DELETE FROM chat_usage_events WHERE scope=?", SCOPE);
        jdbc.update("DELETE FROM chat_spend_periods WHERE scope=?", SCOPE);
        jdbc.update("DELETE FROM chat_billing_state WHERE scope=?", SCOPE);
    }

    @Test
    void successfulChatIsMeteredAndReplayDoesNotChargeAgain() throws Exception {
        Guest owner = guest();
        UUID firstId = UUID.randomUUID();
        JsonNode first = exchange(owner.token(), firstId, "책을 펴 볼게요");
        assertEquals("SUCCEEDED", first.path("status").asText());
        assertEquals("JOY", first.path("utterances").get(0).path("characterKey").asText());
        assertEquals("HOME", provider.observedStudyState.get());
        assertEquals(1, provider.calls.get());

        JsonNode usage = usage(owner.token());
        assertEquals(1, usage.path("daily").path("requests").asInt());
        assertEquals(100, usage.path("daily").path("inputTokens").asInt());
        assertEquals(20, usage.path("daily").path("cachedInputTokens").asInt());
        assertEquals(30, usage.path("daily").path("outputTokens").asInt());
        assertEquals(0, usage.path("daily").path("estimatedUsd").decimalValue()
                .compareTo(new BigDecimal("0.000800000")));
        assertEquals(0, usage.path("daily").path("reservedUsd").decimalValue().signum());
        assertEquals(Long.valueOf(6_280_000L), jdbc.queryForObject("SELECT reserved_nano_usd FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", Long.class, SCOPE, firstId.toString()));

        JsonNode replay = exchange(owner.token(), firstId, "책을 펴 볼게요");
        assertEquals(first.path("id").asText(), replay.path("id").asText());
        assertEquals(1, provider.calls.get());
        assertEquals(1, usage(owner.token()).path("daily").path("requests").asInt());
        assertEquals(first.path("id").asText(), recover(owner.token(), firstId)
                .path("items").get(0).path("id").asText());

        assertEquals("SUCCEEDED", exchange(owner.token(), UUID.randomUUID(), "다음도 시작할게요")
                .path("status").asText());
        JsonNode limited = exchange(owner.token(), UUID.randomUUID(), "세 번째 요청");
        assertEquals("FAILED", limited.path("status").asText());
        assertEquals("AI_DAILY_USAGE_EXCEEDED", limited.path("failureCode").asText());
        assertEquals(2, provider.calls.get());
        assertEquals(2, usage(owner.token()).path("daily").path("requests").asInt());

        Guest other = guest();
        assertEquals(0, usage(other.token()).path("daily").path("requests").asInt());
        assertTrue(recover(other.token(), firstId).path("items").isEmpty());
    }

    @Test
    void defaultRoutingUsesOneMeteredCallAndCanBeReplayedWithTheOriginalCandidates() throws Exception {
        Guest owner = guest();
        UUID requestId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("clientRequestId", requestId, "content", "슬픔아, 오늘 좀 속상했어.");

        JsonNode reply = exchange(owner.token(), payload);
        assertEquals("SUCCEEDED", reply.path("status").asText());
        assertEquals("SAD", reply.path("utterances").get(0).path("characterKey").asText());
        ChatProvider.Turn sent = provider.generatedTurn.get();
        assertTrue(sent.focusedPrompt());
        assertEquals(List.of("SAD"), sent.characterKeys());
        assertEquals(provider.quotedTurn.get(), sent);
        assertEquals(1, provider.calls.get());
        assertEquals(1, usage(owner.token()).path("daily").path("requests").asInt());

        assertEquals(reply, exchange(owner.token(), payload));
        assertEquals(reply.path("id").asText(), recover(owner.token(), requestId)
            .path("items").get(0).path("id").asText());
        assertEquals(1, provider.calls.get());
    }

    @Test
    void creditFailureTripsDurableBreakerAndRecoveryKeepsOriginalFailure() throws Exception {
        Guest owner = guest();
        UUID failedId = UUID.randomUUID();
        JsonNode failed = exchange(owner.token(), failedId, "credit_exhausted");
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("AI_CREDIT_EXHAUSTED", failed.path("failureCode").asText());
        assertEquals(1, provider.calls.get());
        assertEquals("AI_CREDIT_EXHAUSTED", jdbc.queryForObject(
                "SELECT reason FROM chat_billing_state WHERE scope=?", String.class, SCOPE));
        assertEquals("AI_CREDIT_EXHAUSTED", usage(owner.token()).path("availability").asText());

        UUID nextId = UUID.randomUUID();
        JsonNode blocked = exchange(owner.token(), nextId, "다시 말하기");
        assertEquals("FAILED", blocked.path("status").asText());
        assertEquals("AI_CREDIT_EXHAUSTED", blocked.path("failureCode").asText());
        assertEquals(1, provider.calls.get());
        assertEquals(1, usage(owner.token()).path("daily").path("requests").asInt());
        assertEquals("AI_CREDIT_EXHAUSTED", recover(owner.token(), failedId)
                .path("items").get(0).path("failureCode").asText());
        assertEquals(blocked.path("id").asText(), exchange(owner.token(), nextId, "다시 말하기")
                .path("id").asText());
    }

    @Test
    void pausedRoomStateComesFromServerDespiteUserClaimingToStudy() throws Exception {
        Guest owner = guest();
        String startBody = mapper.writeValueAsString(Map.of("clientRequestId", UUID.randomUUID().toString()));
        JsonNode started = mapper.readTree(mvc.perform(post("/api/v1/study-sessions")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content(startBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String sessionId = started.path("id").asText();
        assertEquals("STUDYING", started.path("status").asText());

        JsonNode paused = mapper.readTree(mvc.perform(patch("/api/v1/study-sessions/" + sessionId)
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PAUSE\",\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("PAUSED", paused.path("status").asText());

        JsonNode chat = exchange(owner.token(), UUID.randomUUID(), "저 지금 공부하고 있어요",
                UUID.fromString(sessionId));
        assertEquals("SUCCEEDED", chat.path("status").asText());
        assertEquals("PAUSED", provider.observedStudyState.get());
    }

    private Guest guest() throws Exception {
        JsonNode result = mapper.readTree(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID userId = UUID.fromString(result.path("userId").asText());
        users.add(userId);
        return new Guest(userId, result.path("accessToken").asText());
    }

    private JsonNode exchange(String token, UUID requestId, String content) throws Exception {
        return exchange(token, requestId, content, null);
    }

    private JsonNode exchange(String token, UUID requestId, String content, UUID studySessionId) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("clientRequestId", requestId.toString());
        payload.put("content", content);
        payload.put("characterKeys", List.of("JOY"));
        if (studySessionId != null) payload.put("studySessionId", studySessionId.toString());
        return exchange(token, payload);
    }

    private JsonNode exchange(String token, Map<String, Object> payload) throws Exception {
        String body = mapper.writeValueAsString(payload);
        return mapper.readTree(mvc.perform(post("/api/v1/chat/exchanges")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode usage(String token) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/chat/usage")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode recover(String token, UUID requestId) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/v1/chat/exchanges")
                        .param("clientRequestId", requestId.toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private record Guest(UUID id, String token) { }

    @TestConfiguration
    static class FakeProviderConfig {
        @Bean @Primary FakeMeteredProvider fakeMeteredProvider() { return new FakeMeteredProvider(); }
    }

    static class FakeMeteredProvider implements ChatProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> observedStudyState = new AtomicReference<>();
        final AtomicReference<Turn> quotedTurn = new AtomicReference<>();
        final AtomicReference<Turn> generatedTurn = new AtomicReference<>();

        @Override
        public CostQuote quote(Turn turn) {
            quotedTurn.set(turn);
            return ChatProvider.super.quote(turn);
        }

        @Override
        public Reply generate(Turn turn) {
            generatedTurn.set(turn);
            return ChatProvider.super.generate(turn);
        }

        @Override
        public CostQuote quote(String content, String priorUser, String priorAssistant,
                               List<String> characterKeys) {
            return new CostQuote("gpt-6.1-sol", 1000, 128);
        }

        @Override
        public Reply generate(String content, String priorUser, String priorAssistant,
                              List<String> characterKeys) {
            calls.incrementAndGet();
            if (content.equals("credit_exhausted")) {
                throw new ChatProviderException("AI_CREDIT_EXHAUSTED", false, null);
            }
            return new Reply(List.of(new Utterance(characterKeys.getFirst(), "우리 같이 한 걸음 시작해요.")),
                    new Usage(100, 20, 30));
        }

        @Override
        public Reply generate(String content, String priorUser, String priorAssistant,
                              List<String> characterKeys, String studyState) {
            observedStudyState.set(studyState);
            return generate(content, priorUser, priorAssistant, characterKeys);
        }
    }
}
