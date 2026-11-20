package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.chat.ChatBudgetService;
import kr.ganzithon.backend.chat.ChatDto;
import kr.ganzithon.backend.chat.ChatMemoryService;
import kr.ganzithon.backend.chat.ChatProvider;
import kr.ganzithon.backend.chat.ChatService;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.identity.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

/** Independent QA scenario: two server processes share the same durable conversation history. */
@SpringBootTest(properties = "ganzi.chat.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
@Import(TwoInstancePreviousTurnTest.Config.class)
class TwoInstancePreviousTurnTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ChatBudgetService budget;
    @Autowired ChatMemoryService memory;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectProvider<ChatProvider> providers;
    @Autowired IdentityService identities;
    @Autowired RecordingProvider provider;

    UUID owner;

    @AfterEach void cleanup() {
        if (owner != null) identities.deleteCurrentUser(owner);
    }

    @Test void staleInstanceMustNotAcceptAnOlderPreviousTurn() throws Exception {
        JsonNode guest = mapper.readTree(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        owner = UUID.fromString(guest.required("userId").asText());
        ChatService instanceA = new ChatService(jdbc, clock, providers, budget, memory, transactions, 30);
        ChatService instanceB = new ChatService(jdbc, clock, providers, budget, memory, transactions, 30);

        UUID firstId = UUID.randomUUID();
        UUID newerId = UUID.randomUUID();
        assertEquals("SUCCEEDED", instanceA.create(owner, request(firstId, null)).status());
        assertEquals("SUCCEEDED", instanceB.create(owner,
                new ChatDto.Request(newerId, "두 번째 공부 내용", null, firstId, List.of("JOY"))).status());
        int calls = provider.calls.get();

        // A still caches the first turn. The database has a newer completed turn from B.
        ApiException rejection = assertThrows(ApiException.class,
                () -> instanceA.create(owner, request(UUID.randomUUID(), firstId)));
        assertEquals("INVALID_PREVIOUS_EXCHANGE", rejection.code());
        assertEquals(calls, provider.calls.get());

        ChatDto.Exchange accepted = instanceA.create(owner, request(UUID.randomUUID(), newerId));
        assertEquals("SUCCEEDED", accepted.status());
        assertEquals("두 번째 공부 내용", provider.previousUser.get());
        assertEquals(calls + 1, provider.calls.get());
    }

    @Test void oneServerReplaysRepliesAndKeepsRoomContextSeparateWhenCharactersChange() throws Exception {
        JsonNode guest = mapper.readTree(mvc.perform(post("/api/v1/guest-sessions"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        owner = UUID.fromString(guest.required("userId").asText());
        String bearer = "Bearer " + guest.required("accessToken").asText();
        ChatService server = new ChatService(jdbc, clock, providers, budget, memory, transactions, 30);

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ChatDto.Exchange first = server.create(owner, request(firstId, null));
        assertEquals("SUCCEEDED", first.status());
        ChatDto.Request next = new ChatDto.Request(secondId, "이어서 말할게", null, firstId, List.of("SAD"));
        ChatDto.Exchange second = server.create(owner, next);
        assertEquals("SUCCEEDED", second.status());
        assertEquals("오늘 공부를 이어갈게", provider.previousUser.get());
        assertEquals("SAD", second.utterances().getFirst().characterKey());
        assertEquals(second, server.create(owner, next));
        assertEquals(2, provider.calls.get());

        JsonNode study = mapper.readTree(mvc.perform(post("/api/v1/study-sessions")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(java.util.Map.of("clientRequestId", UUID.randomUUID()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID room = UUID.fromString(study.required("id").asText());
        UUID roomTurnId = UUID.randomUUID();
        ChatDto.Request roomRequest = new ChatDto.Request(roomTurnId, "공부방에서 질문할게", room, null,
                List.of("JOY"));
        ChatDto.Exchange roomReply = server.create(owner, roomRequest);
        assertEquals("SUCCEEDED", roomReply.status());
        assertNull(provider.previousUser.get());

        mvc.perform(patch("/api/v1/study-sessions/" + room)
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"END\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        assertEquals(roomReply, server.recover(owner, roomTurnId).items().getFirst());
        assertEquals(roomReply, server.create(owner, roomRequest));
        assertEquals(3, provider.calls.get());
        assertEquals(3, memory.history(owner, 10, null).items().size());
    }

    private static ChatDto.Request request(UUID id, UUID previous) {
        return new ChatDto.Request(id, "오늘 공부를 이어갈게", null, previous, List.of("JOY"));
    }

    @TestConfiguration static class Config {
        @Bean @Primary RecordingProvider recordingProvider() { return new RecordingProvider(); }
    }

    static class RecordingProvider implements ChatProvider {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> previousUser = new AtomicReference<>();
        @Override public Reply generate(String user, String previous, String assistant, List<String> keys) {
            calls.incrementAndGet();
            previousUser.set(previous);
            return new Reply(List.of(new Utterance(keys.getFirst(), "좋아, 이어가자.")));
        }
    }
}
