package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.chat.ChatBudgetService;
import kr.ganzithon.backend.chat.ChatDto;
import kr.ganzithon.backend.chat.ChatMemoryService;
import kr.ganzithon.backend.chat.ChatProvider;
import kr.ganzithon.backend.chat.ChatService;
import kr.ganzithon.backend.identity.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "ganzi.chat.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(ChatLongMemoryApiIntegrationTest.FakeConfig.class)
class ChatLongMemoryApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChatMemoryService memory;
    @Autowired IdentityService identities;
    @Autowired ChatBudgetService budget;
    @Autowired ObjectProvider<ChatProvider> providers;
    @Autowired Clock clock;
    @Autowired TransactionTemplate transactions;
    @Autowired MemoryAwareProvider provider;
    private final List<UUID> owners = new ArrayList<>();

    @BeforeEach void reset() { provider.calls.set(0); provider.context.set(null); }
    @AfterEach void cleanup() { owners.forEach(identities::deleteCurrentUser); }

    @Test void aNewRoomReceivesTheSameOwnersPreviousDayMemory() throws Exception {
        Guest owner = guest();
        var yesterday = new ChatDto.Request(UUID.randomUUID(), "토익 목표는 850점이야", UUID.randomUUID(),
            null, List.of("JOY"));
        Instant at = Instant.now().minus(1, ChronoUnit.DAYS);
        var saved = new ChatDto.Exchange(UUID.randomUUID(), yesterday.clientRequestId(),
            yesterday.studySessionId(), "SUCCEEDED", List.of(new ChatDto.Utterance("JOY", "함께해 보자.")),
            null, at, at);
        memory.save(owner.id(), yesterday, saved, "a".repeat(64));
        JsonNode study = json(post("/api/v1/study-sessions"), owner,
            Map.of("clientRequestId", UUID.randomUUID()), 201);
        JsonNode reply = json(post("/api/v1/chat/exchanges"), owner,
            Map.of("clientRequestId", UUID.randomUUID(), "content", "어제 목표 기억나?",
                "studySessionId", study.path("id").asText(), "characterKeys", List.of("JOY")), 200);
        assertEquals("SUCCEEDED", reply.path("status").asText());
        assertTrue(provider.context.get().contains("850"));
        assertTrue(provider.context.get().contains("asOf"));
        assertTrue(provider.context.get().codePointCount(0, provider.context.get().length()) <= 6000);
    }

    @Test void aFreshServiceRecoversAndReplaysWithoutAnotherProviderCall() throws Exception {
        Guest owner = guest();
        UUID requestId = UUID.randomUUID();
        var input = new ChatDto.Request(requestId, "도서관에서 시작할게", null, null, List.of("JOY"));
        JsonNode first = json(post("/api/v1/chat/exchanges"), owner,
            Map.of("clientRequestId", requestId, "content", input.content(), "characterKeys", input.characterKeys()), 200);
        ChatService restarted = new ChatService(jdbc, clock, providers, budget, memory, transactions, 30);
        assertEquals(first.path("id").asText(), restarted.recover(owner.id(), requestId).items().getFirst().id().toString());
        assertEquals(first.path("id").asText(), restarted.create(owner.id(), input).id().toString());
        assertEquals(1, provider.calls.get());
        var changed = new ChatDto.Request(requestId, "다른 입력", null, null, List.of("JOY"));
        assertEquals("CLIENT_REQUEST_ID_REUSED", assertThrows(
            kr.ganzithon.backend.common.ApiException.class, () -> restarted.create(owner.id(), changed)).code());
    }

    @Test void historyIsPrivateAndMalformedCursorsAndCorruptRowsAreNotExposed() throws Exception {
        Guest owner = guest();
        Guest other = guest();
        JsonNode first = json(post("/api/v1/chat/exchanges"), owner,
            Map.of("clientRequestId", UUID.randomUUID(), "content", "천문학 책을 읽을래", "characterKeys", List.of("JOY")), 200);
        JsonNode history = getHistory(owner, null, 200);
        assertEquals(1, history.path("items").size());
        assertEquals("천문학 책을 읽을래", history.path("items").get(0).path("content").asText());
        assertEquals(0, getHistory(other, null, 200).path("items").size());
        assertEquals("VALIDATION_FAILED", getHistory(owner, "bad!", 400).path("code").asText());
        mvc.perform(get("/api/v1/chat/history")).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE chat_memory SET raw_payload='tampered' WHERE id=?", first.path("id").asText());
        JsonNode healthyPage = getHistory(owner, null, 200);
        assertEquals(0, healthyPage.path("items").size());
        assertFalse(healthyPage.toString().contains("tampered"));
        ChatService restarted = new ChatService(jdbc, clock, providers, budget, memory, transactions, 30);
        var error = assertThrows(kr.ganzithon.backend.common.ApiException.class,
            () -> restarted.recover(owner.id(), UUID.fromString(first.path("clientRequestId").asText())));
        assertEquals("CHAT_HISTORY_UNAVAILABLE", error.code());
    }

    private JsonNode getHistory(Guest owner, String cursor, int expected) throws Exception {
        var request = get("/api/v1/chat/history").header("Authorization", "Bearer " + owner.token());
        if (cursor != null) request.param("cursor", cursor);
        return mapper.readTree(mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private Guest guest() throws Exception {
        JsonNode value = mapper.readTree(mvc.perform(post("/api/v1/guest-sessions"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(value.path("userId").asText());
        owners.add(id);
        return new Guest(id, value.path("accessToken").asText());
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                          Guest owner, Object body, int expected) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization", "Bearer " + owner.token())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
            .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private record Guest(UUID id, String token) { }
    @TestConfiguration static class FakeConfig {
        @Bean @Primary MemoryAwareProvider memoryAwareProvider() { return new MemoryAwareProvider(); }
    }
    static class MemoryAwareProvider implements ChatProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> context = new AtomicReference<>();
        @Override public Reply generate(String user, String previous, String assistant, List<String> keys) {
            throw new AssertionError("Durable memory must be passed through the bounded provider overload");
        }
        @Override public Reply generate(String user, String previous, String assistant, List<String> keys,
                                        String studyState, String memoryContext) {
            calls.incrementAndGet();
            context.set(memoryContext);
            return new Reply(List.of(new Utterance("JOY", "친구랑 함께해 보자.")));
        }
    }
}
