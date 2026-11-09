package kr.ganzithon.backend;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ganzithon.backend.chat.ChatBudgetService;
import kr.ganzithon.backend.chat.ChatDto;
import kr.ganzithon.backend.chat.ChatMemoryService;
import kr.ganzithon.backend.chat.ChatProvider;
import kr.ganzithon.backend.chat.ChatService;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.identity.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class ChatBudgetIntegrationTest {
    private static final String CONFIG_SCOPE = scopeName();
    private static final ChatProvider.CostQuote QUOTE = new ChatProvider.CostQuote("gpt-6-luna", 100, 10);

    @DynamicPropertySource
    static void budgetProperties(DynamicPropertyRegistry properties) {
        properties.add("ganzi.chat.budget.scope", () -> CONFIG_SCOPE);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ChatBudgetService configuredBudget;
    @Autowired ChatMemoryService memory;
    @Autowired IdentityService identities;
    private final List<String> scopes = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void cleanOnlyThisTestsFixtures() {
        for (String scope : scopes) {
            jdbc.update("DELETE FROM chat_usage_events WHERE scope=?", scope);
            jdbc.update("DELETE FROM chat_spend_periods WHERE scope=?", scope);
            jdbc.update("DELETE FROM chat_billing_state WHERE scope=?", scope);
        }
        for (UUID user : users) jdbc.update("DELETE FROM users WHERE id=?", user.toString());
    }

    @Test
    void configuredBeanPersistsActualUsageAndRejectsDuplicateAdmissionAcrossInstances() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        UUID request = UUID.randomUUID();
        ChatBudgetService.Reservation reservation = configuredBudget.reserve(user, request, "a".repeat(64), QUOTE);
        configuredBudget.settle(reservation, new ChatProvider.Usage(10, 2, 3), false, null);
        assertEquals(new BigDecimal("0.000004000"), configuredBudget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), configuredBudget.usage(user).daily().reservedUsd());

        ChatBudgetService restarted = budget(CONFIG_SCOPE, "1", "10", 100, 1);
        ApiException same = assertThrows(ApiException.class,
                () -> tx(() -> restarted.reserve(user, request, "a".repeat(64), QUOTE)));
        assertEquals("CHAT_RESULT_UNAVAILABLE", same.code());
        ApiException changed = assertThrows(ApiException.class,
                () -> tx(() -> restarted.reserve(user, request, "b".repeat(64), QUOTE)));
        assertEquals("CLIENT_REQUEST_ID_REUSED", changed.code());
        assertEquals(1, configuredBudget.usage(user).daily().requests());
    }

    @Test
    void competingServiceReturnsInProgressAndReplaysTheRealResult() throws Exception {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        ChatProvider provider = provider(() -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        });
        ChatService first = chat(provider, configuredBudget);
        ChatService other = chat(provider, configuredBudget);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<ChatDto.Exchange> pending = pool.submit(() -> first.create(user, request));
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS));
                assertEquals("REQUEST_IN_PROGRESS", assertThrows(ApiException.class,
                        () -> other.create(user, request)).code());
                assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
            } finally {
                release.countDown();
            }
            ChatDto.Exchange result = pending.get(10, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", result.status());
            assertEquals(result, other.create(user, request));
            assertEquals(1, calls.get());
        }
    }

    @Test
    void interruptedCompletionDoesNotCommitTheResultOrFreeTheReservation() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        ChatProvider provider = provider(calls::incrementAndGet);
        ChatBudgetService failingBudget = mock(ChatBudgetService.class);
        when(failingBudget.reserve(eq(user), eq(request.clientRequestId()), anyString(), any()))
                .thenAnswer(call -> configuredBudget.reserve(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)));
        doAnswer(call -> {
            configuredBudget.settle(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            throw new IllegalStateException("simulated commit failure");
        }).when(failingBudget).settle(any(), any(), anyBoolean(), isNull());

        assertEquals("AI_USAGE_RECORD_FAILED", chat(provider, failingBudget).create(user, request).failureCode());
        assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
        assertEquals(new BigDecimal("0.000030000"), configuredBudget.usage(user).daily().reservedUsd());
        assertEquals("REQUEST_IN_PROGRESS", assertThrows(ApiException.class,
                () -> chat(provider, configuredBudget).create(user, request)).code());

        jdbc.update("UPDATE chat_usage_events SET created_at=? WHERE scope=? AND client_request_id=?",
                Timestamp.from(clock.instant().minus(6, ChronoUnit.MINUTES)), CONFIG_SCOPE,
                request.clientRequestId().toString());
        configuredBudget.settleAbandonedReservations();
        configuredBudget.settleAbandonedReservations();
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", String.class, CONFIG_SCOPE, request.clientRequestId().toString()));
        assertEquals(new BigDecimal("0.000030000"), configuredBudget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), configuredBudget.usage(user).daily().reservedUsd());
        assertEquals("CHAT_RESULT_UNAVAILABLE", chat(provider, configuredBudget).create(user, request).failureCode());
        assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
        assertEquals(1, calls.get());
    }

    private ChatDto.Request request() {
        return new ChatDto.Request(UUID.randomUUID(), "오늘 공부 시작할래", null, null, List.of("JOY"));
    }

    private ChatService chat(ChatProvider provider, ChatBudgetService budget) {
        @SuppressWarnings("unchecked") ObjectProvider<ChatProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        return new ChatService(jdbc, clock, providers, budget, memory, new TransactionTemplate(transactions), 30);
    }

    private ChatProvider provider(Runnable beforeReply) {
        return new ChatProvider() {
            @Override public CostQuote quote(String user, String previous, String assistant, List<String> keys) {
                return QUOTE;
            }
            @Override public Reply generate(String user, String previous, String assistant, List<String> keys) {
                beforeReply.run();
                return new Reply(List.of(new Utterance("JOY", "같이 시작해 보자.")), new Usage(10, 2, 3));
            }
        };
    }

    @Test
    void concurrentOwnersCannotReserveMoreThanTheSharedDailyCap() throws Exception {
        String scope = scope();
        ChatBudgetService budget = budget(scope, "0.00006", "0.00006", 100, 1);
        List<UUID> owners = new ArrayList<>();
        for (int i = 0; i < 6; i++) owners.add(user());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Object>> attempts = new ArrayList<>();
        try {
            for (UUID owner : owners) {
                attempts.add(pool.submit(() -> {
                    start.await();
                    try { return tx(() -> budget.reserve(owner, UUID.randomUUID(), "c".repeat(64), QUOTE)); }
                    catch (ApiException ex) { return ex; }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Object> attempt : attempts) {
                Object result = attempt.get();
                if (result instanceof ChatBudgetService.Reservation) admitted++;
                else assertEquals("AI_BUDGET_EXCEEDED", ((ApiException) result).code());
            }
            assertEquals(2, admitted);
            Long reserved = jdbc.queryForObject("SELECT reserved_nano_usd FROM chat_spend_periods "
                    + "WHERE scope=? AND period_kind='DAY'", Long.class, scope);
            assertEquals(Long.valueOf(60_000L), reserved);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unknownProviderOutcomeChargesFullReservationAndRepeatedSettlementIsIdempotent() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService budget = budget(scope, "1", "10", 100, 1);
        ChatBudgetService.Reservation reservation = tx(() -> budget.reserve(
                user, UUID.randomUUID(), "d".repeat(64), QUOTE));
        tx(() -> { budget.settle(reservation, null, true, "AI_PROVIDER_FAILED"); return null; });
        tx(() -> { budget.settle(reservation, null, true, "AI_PROVIDER_FAILED"); return null; });
        assertEquals(new BigDecimal("0.000030000"), budget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), budget.usage(user).daily().reservedUsd());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
    }

    @Test
    void creditBlockSurvivesNewServiceAndEpochBumpDoesNotResetSpending() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService firstEpoch = budget(scope, "0.00006", "0.00006", 100, 1);
        ChatBudgetService.Reservation first = tx(() -> firstEpoch.reserve(
                user, UUID.randomUUID(), "e".repeat(64), QUOTE));
        tx(() -> { firstEpoch.settle(first, null, true, "AI_CREDIT_EXHAUSTED"); return null; });
        ChatBudgetService restarted = budget(scope, "0.00006", "0.00006", 100, 1);
        ApiException blocked = assertThrows(ApiException.class,
                () -> tx(() -> restarted.reserve(user, UUID.randomUUID(), "f".repeat(64), QUOTE)));
        assertEquals("AI_CREDIT_EXHAUSTED", blocked.code());

        ChatBudgetService nextEpoch = budget(scope, "0.00006", "0.00006", 100, 2);
        ChatBudgetService.Reservation second = tx(() -> nextEpoch.reserve(
                user, UUID.randomUUID(), "g".repeat(64), QUOTE));
        assertEquals(new BigDecimal("0.000030000"), nextEpoch.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000030000"), nextEpoch.usage(user).daily().reservedUsd());
        ApiException overCap = assertThrows(ApiException.class,
                () -> tx(() -> nextEpoch.reserve(user, UUID.randomUUID(), "h".repeat(64), QUOTE)));
        assertEquals("AI_BUDGET_EXCEEDED", overCap.code());
        tx(() -> { nextEpoch.settle(second, null, true, "AI_PROVIDER_FAILED"); return null; });
    }

    @Test
    void deletingAccountScrubsOwnerAndHashButInFlightReservationStillSettles() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService budget = budget(scope, "1", "10", 100, 1);
        ChatBudgetService.Reservation reservation = tx(() -> budget.reserve(
                user, UUID.randomUUID(), "i".repeat(64), QUOTE));
        identities.deleteCurrentUser(user);
        assertNull(jdbc.queryForObject("SELECT user_id FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
        assertNull(jdbc.queryForObject("SELECT request_hash FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
        tx(() -> { budget.settle(reservation, null, true, "AI_PROVIDER_FAILED"); return null; });
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
        assertEquals(Long.valueOf(30_000L), jdbc.queryForObject("SELECT charged_nano_usd FROM chat_spend_periods "
                + "WHERE scope=? AND period_kind='DAY'", Long.class, scope));
    }

    @Test
    void malformedProviderUsageCannotReleaseAChargeableReservation() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService budget = budget(scope, "1", "10", 100, 1);
        ChatBudgetService.Reservation reservation = tx(() -> budget.reserve(
                user, UUID.randomUUID(), "j".repeat(64), QUOTE));

        // A non-null provider report with impossible cache counts is still an unknown billable call.
        tx(() -> { budget.settle(reservation, new ChatProvider.Usage(10, 11, 3), false,
                "AI_PROVIDER_FAILED"); return null; });

        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
        assertEquals(new BigDecimal("0.000030000"), budget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), budget.usage(user).daily().reservedUsd());
    }

    @Test
    void usageAboveReservationRecordsActualCostAndBlocksFurtherAdmission() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService budget = budget(scope, "1", "10", 100, 1);
        ChatBudgetService.Reservation reservation = tx(() -> budget.reserve(
                user, UUID.randomUUID(), "k".repeat(64), QUOTE));

        tx(() -> { budget.settle(reservation, new ChatProvider.Usage(1000, 0, 100), false,
                null); return null; });

        assertEquals("SETTLED", jdbc.queryForObject("SELECT status FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
        assertEquals(new BigDecimal("0.000300000"), budget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), budget.usage(user).daily().reservedUsd());
        assertEquals("AI_USAGE_EXCEEDED_RESERVATION", budget.usage(user).availability());
        ApiException blocked = assertThrows(ApiException.class,
                () -> tx(() -> budget.reserve(user, UUID.randomUUID(), "l".repeat(64), QUOTE)));
        assertEquals("AI_USAGE_EXCEEDED_RESERVATION", blocked.code());
    }

    private ChatBudgetService budget(String scope, String day, String month, int perUser, long epoch) {
        return new ChatBudgetService(jdbc, clock, scope, new BigDecimal(day), new BigDecimal(month), perUser, epoch);
    }

    private <T> T tx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,created_at) VALUES (?,?)", id.toString(), Timestamp.from(Instant.now()));
        users.add(id);
        return id;
    }

    private String scope() {
        String scope = scopeName();
        scopes.add(scope);
        return scope;
    }

    private static String scopeName() {
        return "budget_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
