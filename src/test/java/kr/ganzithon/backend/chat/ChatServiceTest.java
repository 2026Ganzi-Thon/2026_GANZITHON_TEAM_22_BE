package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void successfulTurnsReachTheProviderWithoutClientPreviousIdAndReplayDoesNotDuplicateThem() {
        stubOwner();
        List<ChatProvider.Turn> received = new java.util.ArrayList<>();
        ChatProvider model = new ChatProvider() {
            @Override public boolean usesSessionTranscript() { return true; }
            @Override public Reply generate(String content, String previousUser, String previousAssistant,
                                            List<String> keys) {
                throw new AssertionError("Turn overload must be used");
            }
            @Override public Reply generate(Turn turn) {
                received.add(turn);
                return new Reply(List.of(new Utterance("JOY", "답변 " + received.size())));
            }
        };
        when(available.getIfAvailable()).thenReturn(model);
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        ChatDto.Request first = new ChatDto.Request(UUID.randomUUID(), "첫 원문", null, null, List.of("JOY"));
        ChatDto.Request second = new ChatDto.Request(UUID.randomUUID(), "둘째 원문", null, null, List.of("JOY"));
        service.create(userId, first);
        service.create(userId, first);
        service.create(userId, second);
        assertEquals(2, received.size());
        assertEquals(1, received.get(0).currentTurn());
        assertTrue(received.get(0).sessionTranscript().isEmpty());
        assertEquals(2, received.get(1).currentTurn());
        assertEquals(List.of(new ChatProvider.TranscriptTurn(1, "첫 원문",
                List.of(new ChatProvider.Utterance("JOY", "답변 1")))),
                received.get(1).sessionTranscript());
    }

    @Test
    void quoteAndGenerateReceiveTheSameSelectedTurn() {
        stubOwner();
        AtomicReference<ChatProvider.Turn> quoted = new AtomicReference<>();
        when(available.getIfAvailable()).thenReturn(new ChatProvider() {
            @Override public Reply generate(String content, String previousUser, String previousAssistant,
                                            List<String> keys) {
                throw new AssertionError("Turn overload must be used");
            }
            @Override public CostQuote quote(Turn turn) {
                quoted.set(turn);
                return null;
            }
            @Override public Reply generate(Turn turn) {
                assertTrue(quoted.get() == turn);
                return new Reply(List.of(new Utterance("JOY", "좋아요.")));
            }
        });
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        assertEquals("SUCCEEDED", service.create(userId, new ChatDto.Request(UUID.randomUUID(),
                "안녕", null, null, List.of("JOY"))).status());
    }

    @Test
    void legacyProviderRetainsLongTermContextWithoutTranscriptExclusion() {
        stubOwner();
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.context(userId, "안녕", null)).thenReturn("legacy-context");
        when(memory.save(eq(userId), any(ChatDto.Request.class), any(ChatDto.Exchange.class), any(String.class)))
                .thenAnswer(inv -> new ChatMemoryService.Saved(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        AtomicReference<ChatProvider.Turn> received = new AtomicReference<>();
        when(available.getIfAvailable()).thenReturn(new ChatProvider() {
            @Override public Reply generate(String content, String previousUser, String previousAssistant,
                                            List<String> keys) { throw new AssertionError(); }
            @Override public Reply generate(Turn turn) {
                received.set(turn);
                return new Reply(List.of(new Utterance("JOY", "반가워요.")));
            }
        });
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available,
                null, memory, null, 30);
        assertEquals("SUCCEEDED", service.create(userId, new ChatDto.Request(UUID.randomUUID(),
                "안녕", null, null, List.of("JOY"))).status());
        assertEquals("legacy-context", received.get().memoryContext());
        assertTrue(received.get().sessionTranscript().isEmpty());
        verify(memory, never()).transcript(userId, null);
    }

    @Test
    void transientOwnershipRecheckFailureAfterProviderPreservesTheReply() {
        doReturn(List.of(userId)).doThrow(new DataAccessResourceFailureException("ownership read interrupted"))
                .doReturn(List.of(userId)).when(jdbc).query(
                        eq("SELECT id FROM users WHERE id = ?"), any(RowMapper.class), eq(userId.toString()));
        var calls = new AtomicInteger();
        when(available.getIfAvailable()).thenReturn((content, priorUser, priorAssistant, keys) -> {
            calls.incrementAndGet();
            return new ChatProvider.Reply(List.of(new ChatProvider.Utterance("JOY", "같이 시작해요.")));
        });
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        var request = new ChatDto.Request(UUID.randomUUID(), "오늘 공부를 시작할래요", null, null, List.of("JOY"));

        ChatDto.Exchange result = service.create(userId, request);
        assertEquals("SUCCEEDED", result.status());
        assertEquals(result, service.recover(userId, request.clientRequestId()).items().getFirst());
        assertEquals(1, calls.get());
    }

    @Test
    void stalledCompletionKeepsSameUserInFlightAndAllowsAnotherUserToFinish() throws Exception {
        stubOwner();
        UUID otherUser = UUID.randomUUID();
        doReturn(List.of(otherUser)).when(jdbc).query(
                eq("SELECT id FROM users WHERE id = ?"), any(RowMapper.class), eq(otherUser.toString()));
        when(available.getIfAvailable()).thenReturn((content, priorUser, priorAssistant, keys) ->
                new ChatProvider.Reply(List.of(new ChatProvider.Utterance("JOY", "같이 시작해요."))));
        var retryStarted = new CountDownLatch(1);
        var releaseRetry = new CountDownLatch(1);
        ChatMemoryService memory = mock(ChatMemoryService.class);
        when(memory.save(eq(userId), any(ChatDto.Request.class), any(ChatDto.Exchange.class), any(String.class)))
                .thenThrow(new DataAccessResourceFailureException("completion interrupted"))
                .thenAnswer(inv -> {
                    retryStarted.countDown();
                    assertTrue(releaseRetry.await(10, TimeUnit.SECONDS));
                    return new ChatMemoryService.Saved(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
                });
        when(memory.save(eq(otherUser), any(ChatDto.Request.class), any(ChatDto.Exchange.class), any(String.class)))
                .thenAnswer(inv -> new ChatMemoryService.Saved(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, null, memory, null, 30);
        var request = new ChatDto.Request(UUID.randomUUID(), "공부를 시작할래요", null, null, List.of("JOY"));
        var otherRequest = new ChatDto.Request(UUID.randomUUID(), "책을 읽을래요", null, null, List.of("JOY"));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var pending = workers.submit(() -> service.create(userId, request));
            assertTrue(retryStarted.await(5, TimeUnit.SECONDS));
            workers.submit(() -> {
                assertEquals("PROCESSING", service.recover(userId, request.clientRequestId()).items().getFirst().status());
                assertEquals("REQUEST_IN_PROGRESS",
                        assertThrows(ApiException.class, () -> service.create(userId, request)).code());
                assertEquals("CHAT_IN_PROGRESS",
                        assertThrows(ApiException.class, () -> service.create(userId, otherRequest)).code());
                assertEquals("SUCCEEDED", service.create(otherUser, otherRequest).status());
                service.forgetUser(userId);
            }).get(5, TimeUnit.SECONDS);
            releaseRetry.countDown();
            var deleted = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertEquals("AUTH_REQUIRED", ((ApiException) deleted.getCause()).code());
            assertTrue(service.recover(userId, request.clientRequestId()).items().isEmpty());
        } finally {
            releaseRetry.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void mysqlLockWaitTimeoutStopsCompletionRetries() {
        stubOwner();
        when(available.getIfAvailable()).thenReturn((content, priorUser, priorAssistant, keys) ->
                new ChatProvider.Reply(List.of(new ChatProvider.Utterance("JOY", "같이 시작해요."))));
        ChatMemoryService memory = mock(ChatMemoryService.class);
        var timeout = new SQLException("lock wait elapsed", "HY000", 1205);
        var request = new ChatDto.Request(UUID.randomUUID(), "공부를 시작할래요", null, null, List.of("JOY"));
        when(memory.save(eq(userId), eq(request), any(ChatDto.Exchange.class), any(String.class)))
                .thenThrow(new CannotAcquireLockException("lock wait elapsed", timeout));
        ChatService service = new ChatService(jdbc, Clock.systemUTC(), available, null, memory, null, 30);

        assertEquals("CHAT_MEMORY_WRITE_FAILED", service.create(userId, request).failureCode());
        verify(memory).save(eq(userId), eq(request), any(ChatDto.Exchange.class), any(String.class));
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
    void routingFlagAndCandidateValidationProtectTheExistingResponseContract() {
        stubOwner();
        List<String> candidates = List.of("JOY", "SAD");
        when(available.getIfAvailable()).thenReturn((content, priorUser, priorAssistant, keys) ->
                new ChatProvider.Reply(List.of(new ChatProvider.Utterance("JOY", "같이 있을게."))));
        var request = new ChatDto.Request(UUID.randomUUID(), "슬픔아, 잠깐 얘기할래?", null, null, candidates);

        ChatService legacy = new ChatService(jdbc, Clock.systemUTC(), available, null, null, null, 30, false);
        assertEquals("SUCCEEDED", legacy.create(userId, request).status());

        ChatService routed = new ChatService(jdbc, Clock.systemUTC(), available, 30);
        ChatDto.Exchange rejected = routed.create(userId, request);
        assertEquals("FAILED", rejected.status());
        assertTrue(rejected.utterances().isEmpty());

        var ambiguous = new ChatDto.Request(UUID.randomUUID(), "오늘 하루가 좀 복잡했어.", null, null, candidates);
        assertEquals("SUCCEEDED", routed.create(userId, ambiguous).status());
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
