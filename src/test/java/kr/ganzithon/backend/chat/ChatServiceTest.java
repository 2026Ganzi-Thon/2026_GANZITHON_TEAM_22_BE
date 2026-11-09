package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {
    private final UUID userId = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatProvider> available = mock(ObjectProvider.class);

    @Test
    void replayAndOneTurnContinuityStayWithinExplicitRequests() {
        stubOwner();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> previousUser = new AtomicReference<>();
        ChatProvider model = (content, priorUser, priorAssistant, keys) -> {
            calls.incrementAndGet();
            previousUser.set(priorUser);
            return new ChatProvider.Reply(List.of(new ChatProvider.Utterance("JOY", "같이 시작해요.")));
        };
        when(available.getIfAvailable()).thenReturn(model);
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        UUID firstId = UUID.randomUUID();
        ChatDto.Request first = new ChatDto.Request(firstId, "책을 펴기 어려워요", null, null, List.of("JOY"));

        ChatDto.Exchange initial = service.create(userId, first);
        assertEquals("SUCCEEDED", initial.status());
        assertEquals(initial, service.create(userId, first));
        assertEquals(1, calls.get());
        assertEquals(1, service.recover(userId, firstId).items().size());
        ApiException reused = assertThrows(ApiException.class, () -> service.create(userId,
                new ChatDto.Request(firstId, "다른 말", null, null, List.of("JOY"))));
        assertEquals("CLIENT_REQUEST_ID_REUSED", reused.code());

        service.create(userId, new ChatDto.Request(UUID.randomUUID(), "한 쪽 읽었어요", null,
                firstId, List.of("JOY")));
        assertEquals("책을 펴기 어려워요", previousUser.get());
        ApiException stale = assertThrows(ApiException.class, () -> service.create(userId,
                new ChatDto.Request(UUID.randomUUID(), "다음", null, firstId, List.of("JOY"))));
        assertEquals("INVALID_PREVIOUS_EXCHANGE", stale.code());
    }

    @Test
    void missingProviderDoesNotConsumeRequestIdAndAbsentRecoveryIsEmpty() {
        stubOwner();
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        UUID requestId = UUID.randomUUID();
        ChatDto.Request request = new ChatDto.Request(requestId, "안녕", null, null, List.of("JOY"));
        ApiException absent = assertThrows(ApiException.class, () -> service.create(userId, request));
        assertEquals(503, absent.status());
        assertEquals("CHAT_NOT_CONFIGURED", absent.code());
        assertTrue(service.recover(userId, requestId).items().isEmpty());
    }

    @Test
    void rateLimitCountsOnlyNewAdmissionsAndKeepsReplayAvailable() {
        stubOwner();
        AtomicInteger calls = new AtomicInteger();
        when(available.getIfAvailable()).thenReturn((content, priorUser, priorAssistant, keys) -> {
            calls.incrementAndGet();
            return new ChatProvider.Reply(List.of(new ChatProvider.Utterance("JOY", "좋아요.")));
        });
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        ChatDto.Request first = null;
        for (int i = 0; i < 10; i++) {
            ChatDto.Request request = new ChatDto.Request(UUID.randomUUID(), "시작 " + i,
                    null, null, List.of("JOY"));
            if (i == 0) first = request;
            assertEquals("SUCCEEDED", service.create(userId, request).status());
        }
        ChatDto.Request replay = first;
        assertEquals("SUCCEEDED", service.create(userId, replay).status());
        ApiException limited = assertThrows(ApiException.class, () -> service.create(userId,
                new ChatDto.Request(UUID.randomUUID(), "열한 번째", null, null, List.of("JOY"))));
        assertEquals(429, limited.status());
        assertEquals("CHAT_RATE_LIMITED", limited.code());
        assertEquals(10, calls.get());
    }

    @Test
    void providerPreflightFailuresAreDocumentedAsFailedExchangeCodes() throws Exception {
        stubOwner();
        when(available.getIfAvailable()).thenReturn((content, priorUser, priorAssistant, keys) -> {
            int status = "VALIDATION_FAILED".equals(content) ? 400 : 500;
            throw new ApiException(status, content, "Provider preflight failed");
        });
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        JsonNode documentedCodes = new ObjectMapper().readTree(
                Path.of("docs", "implemented.openapi.json").toFile())
                .path("components").path("schemas").path("ExchangeDTO")
                .path("properties").path("failureCode").path("anyOf").get(0).path("enum");

        for (String code : List.of("CHAT_REQUEST_FAILED", "VALIDATION_FAILED")) {
            ChatDto.Exchange result = service.create(userId,
                    new ChatDto.Request(UUID.randomUUID(), code, null, null, List.of("JOY")));
            assertEquals("FAILED", result.status());
            assertEquals(code, result.failureCode());
            assertTrue(result.utterances().isEmpty());
            assertTrue(containsText(documentedCodes, code),
                    "OpenAPI must list FAILED.failureCode " + code);
        }
    }

    private static boolean containsText(JsonNode array, String text) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (text.equals(item.asText())) return true;
        return false;
    }

    private void stubOwner() {
        // PortableJdbc maps UUID parameters to CHAR(36) and reads UUID results through RowMapper.
        doReturn(List.of(userId)).when(jdbc).query(
                eq("SELECT id FROM users WHERE id = ?"), any(RowMapper.class), eq(userId.toString()));
    }
}
