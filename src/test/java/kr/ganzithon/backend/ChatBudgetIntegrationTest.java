package kr.ganzithon.backend;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import kr.ganzithon.backend.chat.ChatBudgetService;
import kr.ganzithon.backend.chat.ChatDto;
import kr.ganzithon.backend.chat.ChatMemoryService;
import kr.ganzithon.backend.chat.ChatProvider;
import kr.ganzithon.backend.chat.ChatProviderException;
import kr.ganzithon.backend.chat.ChatService;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.identity.IdentityService;
import kr.ganzithon.backend.study.StudyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class ChatBudgetIntegrationTest {
    private static final String CONFIG_SCOPE = scopeName();
    private static final ChatProvider.CostQuote QUOTE = new ChatProvider.CostQuote("gpt-6-luna", 100, 10);
    private static final ChatProvider.CostQuote SOL_62_QUOTE = new ChatProvider.CostQuote("gpt-6.2-sol", 100, 10);
    private static final ChatProvider.Usage SOL_62_USAGE = new ChatProvider.Usage(100, 30, 3, 30);

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
    @Autowired StudyService studies;
    private final List<String> scopes = new ArrayList<>();
    private final List<UUID> users = new ArrayList<>();

    @AfterEach
    void cleanOnlyThisTestsFixtures() {
        for (String scope : scopes) {
            jdbc.update("DELETE FROM chat_usage_events WHERE scope=?", scope);
            jdbc.update("DELETE FROM chat_spend_periods WHERE scope=?", scope);
            jdbc.update("DELETE FROM chat_billing_state WHERE scope=?", scope);
        }
        for (UUID user : users) {
            jdbc.update("DELETE FROM study_sessions WHERE user_id=?", user.toString());
            jdbc.update("DELETE FROM users WHERE id=?", user.toString());
        }
    }

    @Test
    void impossibleCombinedCacheCountsKeepSol62ReservationCharged() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService budget = budget(scope, "1", "10", 100, 1);
        ChatProvider.CostQuote quote = new ChatProvider.CostQuote("gpt-6.2-sol", 100, 10);
        ChatBudgetService.Reservation reservation = tx(() -> budget.reserve(
                user, UUID.randomUUID(), "y".repeat(64), quote));
        tx(() -> { budget.settle(reservation, new ChatProvider.Usage(100, 80, 3, 30), false, null);
            return null; });
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM chat_usage_events WHERE id=?",
                String.class, reservation.id().toString()));
        assertEquals(new BigDecimal("0.000350000"), budget.usage(user).daily().estimatedUsd());
    }


    @Test
    void sol62ReservesCacheWriteMaximumAndSettlesEachInputClassAtItsPrice() {
        String scope = scope();
        UUID user = user();
        ChatBudgetService budget = budget(scope, "1", "10", 100, 1);
        ChatProvider.CostQuote quote = new ChatProvider.CostQuote("gpt-6.2-sol", 100, 10);
        ChatBudgetService.Reservation reservation = tx(() -> budget.reserve(
                user, UUID.randomUUID(), "z".repeat(64), quote));
        assertEquals(new BigDecimal("0.000350000"), budget.usage(user).daily().reservedUsd());

        tx(() -> { budget.settle(reservation, new ChatProvider.Usage(100, 30, 3, 30), false, null);
            return null; });
        assertEquals(new BigDecimal("0.000188000"), budget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), budget.usage(user).daily().reservedUsd());
        assertEquals(Long.valueOf(30), jdbc.queryForObject(
                "SELECT cache_write_input_tokens FROM chat_usage_events WHERE id=?", Long.class,
                reservation.id().toString()));
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
    void admissionFaultDoesNotReleaseAnotherInstancesReservation() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        ChatProvider provider = provider(calls::incrementAndGet);
        ChatBudgetService failingBudget = mock(ChatBudgetService.class, delegatesTo(configuredBudget));
        doAnswer(call -> {
            configuredBudget.reserve(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            throw new DataAccessResourceFailureException("this attempt did not reach storage");
        }).when(failingBudget).reserve(eq(user), eq(request.clientRequestId()), anyString(), any(), any());

        assertEquals("CHAT_ADMISSION_UNAVAILABLE", assertThrows(ApiException.class,
                () -> chat(provider, failingBudget).create(user, request)).code());
        assertEquals("REQUEST_IN_PROGRESS", assertThrows(ApiException.class,
                () -> chat(provider, configuredBudget).create(user, request)).code());
        assertEquals(new BigDecimal("0.000030000"), configuredBudget.usage(user).daily().reservedUsd());
        assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admissionFailureReturnsRetryable503WithoutStartingProvider(boolean committed) {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        ChatProvider provider = provider(calls::incrementAndGet, SOL_62_QUOTE, SOL_62_USAGE);
        ChatBudgetService failingBudget = mock(ChatBudgetService.class, delegatesTo(configuredBudget));
        doAnswer(call -> {
            UUID reservationId = call.getArgument(4);
            if (committed) {
                configuredBudget.reserve(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), reservationId);
            } else {
                tx(() -> {
                    configuredBudget.reserve(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                            call.getArgument(3), reservationId);
                    throw new DataAccessResourceFailureException("admission transaction rolled back");
                });
            }
            throw new DataAccessResourceFailureException("admission acknowledgement unavailable");
        }).when(failingBudget).reserve(eq(user), eq(request.clientRequestId()), anyString(), any(), any());

        ChatService service = chat(provider, failingBudget);
        ApiException error = assertThrows(ApiException.class, () -> service.create(user, request));
        assertEquals(503, error.status());
        assertEquals("CHAT_ADMISSION_UNAVAILABLE", error.code());
        assertTrue(error.retryable());
        assertTrue(service.recover(user, request.clientRequestId()).items().isEmpty());
        assertEquals(0, calls.get());
        assertEquals(new BigDecimal("0.000000000"), configuredBudget.usage(user).daily().reservedUsd());
        assertEquals(new BigDecimal("0.000000000"), configuredBudget.usage(user).daily().estimatedUsd());
        assertEquals(committed ? 1 : 0, configuredBudget.usage(user).daily().requests());

        ChatService anotherInstance = chat(provider, configuredBudget);
        if (committed) {
            assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM chat_usage_events "
                    + "WHERE scope=? AND client_request_id=?", String.class, CONFIG_SCOPE, request.clientRequestId().toString()));
            assertEquals("CHAT_RESULT_UNAVAILABLE", anotherInstance.create(user, request).failureCode());
            assertEquals(0, calls.get());
        } else {
            assertEquals("SUCCEEDED", anotherInstance.create(user, request).status());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void transientSettlementFailureRollsBackAndReplaysTheSameExchangeWithOneCharge() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        var completionAttempts = new AtomicInteger();
        ChatProvider provider = provider(calls::incrementAndGet, SOL_62_QUOTE, SOL_62_USAGE);
        ChatBudgetService failingBudget = failCompletionSettlement(() -> completionAttempts.getAndIncrement() == 0);

        ChatDto.Exchange result = chat(provider, failingBudget).create(user, request);
        assertEquals("SUCCEEDED", result.status());
        assertEquals(result, chat(provider, configuredBudget).create(user, request));
        assertEquals(2, completionAttempts.get());
        assertEquals(1, calls.get());
        assertSol62Charge(user, request.clientRequestId());
    }

    @Test
    void roomEndingAfterRolledBackCompletionDiscardsReplyAndChargesKnownUsage() {
        assertCompletionAcrossRoomEnd(false, "STUDY_SESSION_ENDED");
    }

    @Test
    void roomEndingAfterCommittedCompletionKeepsItsOriginalReply() {
        assertCompletionAcrossRoomEnd(true, null);
    }

    private void assertCompletionAcrossRoomEnd(boolean commitFirstAttempt, String expectedFailureCode) {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var room = studies.start(user, UUID.randomUUID(), null).session();
        var request = new ChatDto.Request(UUID.randomUUID(), "책을 읽을래요", room.id(), null, List.of("JOY"));
        var calls = new AtomicInteger();
        var completionAttempts = new AtomicInteger();
        var completion = new TransactionTemplate(transactions) {
            @Override public <T> T execute(TransactionCallback<T> callback) {
                if (completionAttempts.incrementAndGet() != 1) return super.execute(callback);
                super.execute(status -> {
                    callback.doInTransaction(status);
                    if (!commitFirstAttempt) status.setRollbackOnly();
                    return null;
                });
                studies.transition(user, room.id(), "END", room.version(), "USER_STOPPED");
                throw new DataAccessResourceFailureException("completion interrupted");
            }
        };
        ChatProvider provider = provider(calls::incrementAndGet, SOL_62_QUOTE, SOL_62_USAGE);

        ChatDto.Exchange result = chat(provider, configuredBudget, completion).create(user, request);
        assertEquals(expectedFailureCode == null ? "SUCCEEDED" : "FAILED", result.status());
        assertEquals(expectedFailureCode, result.failureCode());
        assertEquals(result, chat(provider, configuredBudget).create(user, request));
        assertEquals(2, completionAttempts.get());
        assertEquals(1, calls.get());
        assertSol62Charge(user, request.clientRequestId());
    }

    @Test
    void sustainedCompletionFailureSettlesActualSol62UsageAndDoesNotReadmitTheRequest() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        var completionAttempts = new AtomicInteger();
        ChatProvider provider = provider(calls::incrementAndGet, SOL_62_QUOTE, SOL_62_USAGE);
        ChatBudgetService failingBudget = failCompletionSettlement(() -> completionAttempts.incrementAndGet() > 0);

        ChatDto.Exchange failed = chat(provider, failingBudget).create(user, request);
        assertEquals("AI_USAGE_RECORD_FAILED", failed.failureCode());
        assertEquals(request.clientRequestId(), failed.clientRequestId());
        assertEquals("CHAT_RESULT_UNAVAILABLE", chat(provider, configuredBudget).create(user, request).failureCode());
        assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
        assertEquals(3, completionAttempts.get());
        assertEquals(1, calls.get());
        assertSol62Charge(user, request.clientRequestId());
    }

    @Test
    void repeatedLostCommitAcknowledgementsRecoverTheCommittedExchangeAndChargeOnlyOnce() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        var completionAttempts = new AtomicInteger();
        var commit = new TransactionTemplate(transactions) {
            @Override public <T> T execute(TransactionCallback<T> callback) {
                super.execute(callback);
                completionAttempts.incrementAndGet();
                throw new DataAccessResourceFailureException("lost commit acknowledgement");
            }
        };
        ChatProvider provider = provider(calls::incrementAndGet, SOL_62_QUOTE, SOL_62_USAGE);

        ChatDto.Exchange result = chat(provider, configuredBudget, commit).create(user, request);
        assertEquals("SUCCEEDED", result.status());
        assertEquals(result, chat(provider, configuredBudget).create(user, request));
        assertEquals(3, completionAttempts.get());
        assertEquals(1, calls.get());
        assertSol62Charge(user, request.clientRequestId());
    }

    @Test
    void sustainedMemoryWriteFailureSettlesActualUsageOutsideTheCompletionTransaction() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        ChatProvider provider = provider(calls::incrementAndGet, SOL_62_QUOTE, SOL_62_USAGE);
        ChatMemoryService failingMemory = mock(ChatMemoryService.class, delegatesTo(memory));
        doThrow(new DataAccessResourceFailureException("conversation storage unavailable")).when(failingMemory)
                .save(eq(user), eq(request), any(), anyString());
        ChatService service = chat(provider, configuredBudget, new TransactionTemplate(transactions), failingMemory);

        ChatDto.Exchange failed = service.create(user, request);
        assertEquals("CHAT_MEMORY_WRITE_FAILED", failed.failureCode());
        assertEquals(request.clientRequestId(), failed.clientRequestId());
        assertEquals("CHAT_RESULT_UNAVAILABLE", chat(provider, configuredBudget).create(user, request).failureCode());
        assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
        verify(failingMemory, times(3)).save(eq(user), eq(request), any(), anyString());
        assertEquals(1, calls.get());
        assertSol62Charge(user, request.clientRequestId());
    }

    @Test
    void knownNonbillableProviderFailureSettlesAtZeroWhenHistoryWriteFails() {
        scopes.add(CONFIG_SCOPE);
        UUID user = user();
        var request = request();
        var calls = new AtomicInteger();
        ChatProvider provider = provider(() -> {
            calls.incrementAndGet();
            throw new ChatProviderException("AI_PROVIDER_FAILED", false, null);
        }, SOL_62_QUOTE, null);
        ChatMemoryService failingMemory = mock(ChatMemoryService.class, delegatesTo(memory));
        doThrow(new DataAccessResourceFailureException("conversation storage unavailable")).when(failingMemory)
                .save(eq(user), eq(request), any(), anyString());
        ChatService service = chat(provider, configuredBudget, new TransactionTemplate(transactions), failingMemory);

        ChatDto.Exchange failed = service.create(user, request);
        assertEquals("CHAT_MEMORY_WRITE_FAILED", failed.failureCode());
        assertEquals(request.clientRequestId(), failed.clientRequestId());
        assertEquals("REJECTED", jdbc.queryForObject("SELECT status FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", String.class, CONFIG_SCOPE, request.clientRequestId().toString()));
        assertEquals(0L, jdbc.queryForObject("SELECT charged_nano_usd FROM chat_spend_periods "
                + "WHERE scope=? AND period_kind='DAY'", Long.class, CONFIG_SCOPE));
        assertEquals(new BigDecimal("0.000000000"), configuredBudget.usage(user).daily().reservedUsd());
        assertEquals("CHAT_RESULT_UNAVAILABLE", chat(provider, configuredBudget).create(user, request).failureCode());
        assertTrue(memory.find(user, request.clientRequestId()).isEmpty());
        verify(failingMemory, times(3)).save(eq(user), eq(request), any(), anyString());
        assertEquals(1, configuredBudget.usage(user).daily().requests());
        assertEquals(1, calls.get());
    }

    @Test
    void accountDeletionDuringProviderCallKeepsUsageButLeavesNoReplyForEitherOwner() {
        scopes.add(CONFIG_SCOPE);
        UUID deletedOwner = user();
        UUID otherOwner = user();
        var request = request();
        var calls = new AtomicInteger();
        ChatProvider provider = provider(() -> {
            if (calls.getAndIncrement() == 0) identities.deleteCurrentUser(deletedOwner);
        }, SOL_62_QUOTE, SOL_62_USAGE);
        ChatService service = chat(provider, configuredBudget);

        assertEquals("AUTH_REQUIRED", assertThrows(ApiException.class, () -> service.create(deletedOwner, request)).code());
        assertTrue(memory.find(deletedOwner, request.clientRequestId()).isEmpty());
        assertTrue(service.recover(otherOwner, request.clientRequestId()).items().isEmpty());
        assertEquals("SETTLED", jdbc.queryForObject("SELECT status FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", String.class, CONFIG_SCOPE, request.clientRequestId().toString()));
        assertEquals(188_000L, jdbc.queryForObject("SELECT charged_nano_usd FROM chat_spend_periods "
                + "WHERE scope=? AND period_kind='DAY'", Long.class, CONFIG_SCOPE));
        assertNull(jdbc.queryForObject("SELECT request_hash FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", String.class, CONFIG_SCOPE, request.clientRequestId().toString()));
        assertEquals("SUCCEEDED", service.create(otherOwner, request).status());
        assertEquals(2, calls.get());
    }

    private ChatBudgetService failCompletionSettlement(BooleanSupplier fail) {
        ChatBudgetService failingBudget = mock(ChatBudgetService.class, delegatesTo(configuredBudget));
        doAnswer(call -> {
            configuredBudget.settle(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
            if (TransactionSynchronizationManager.isActualTransactionActive() && fail.getAsBoolean()) {
                throw new DataAccessResourceFailureException("completion transaction interrupted");
            }
            return null;
        }).when(failingBudget).settle(any(), any(), anyBoolean(), isNull());
        return failingBudget;
    }

    private void assertSol62Charge(UUID user, UUID requestId) {
        assertEquals("SETTLED", jdbc.queryForObject("SELECT status FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", String.class, CONFIG_SCOPE, requestId.toString()));
        assertEquals(30L, jdbc.queryForObject("SELECT cached_input_tokens FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", Long.class, CONFIG_SCOPE, requestId.toString()));
        assertEquals(30L, jdbc.queryForObject("SELECT cache_write_input_tokens FROM chat_usage_events "
                + "WHERE scope=? AND client_request_id=?", Long.class, CONFIG_SCOPE, requestId.toString()));
        assertEquals(new BigDecimal("0.000188000"), configuredBudget.usage(user).daily().estimatedUsd());
        assertEquals(new BigDecimal("0.000000000"), configuredBudget.usage(user).daily().reservedUsd());
        assertEquals(1, configuredBudget.usage(user).daily().requests());
        assertEquals(188_000L, jdbc.queryForObject("SELECT charged_nano_usd FROM chat_spend_periods "
                + "WHERE scope=? AND period_kind='DAY'", Long.class, CONFIG_SCOPE));
    }

    private ChatDto.Request request() {
        return new ChatDto.Request(UUID.randomUUID(), "오늘 공부 시작할래", null, null, List.of("JOY"));
    }

    private ChatService chat(ChatProvider provider, ChatBudgetService budget) {
        return chat(provider, budget, new TransactionTemplate(transactions));
    }

    private ChatService chat(ChatProvider provider, ChatBudgetService budget, TransactionTemplate completion) {
        return chat(provider, budget, completion, memory);
    }

    private ChatService chat(ChatProvider provider, ChatBudgetService budget, TransactionTemplate completion,
                             ChatMemoryService chatMemory) {
        @SuppressWarnings("unchecked") ObjectProvider<ChatProvider> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(provider);
        return new ChatService(jdbc, clock, providers, budget, chatMemory, completion, 30);
    }

    private ChatProvider provider(Runnable beforeReply) {
        return provider(beforeReply, QUOTE, new ChatProvider.Usage(10, 2, 3));
    }

    private ChatProvider provider(Runnable beforeReply, ChatProvider.CostQuote cost, ChatProvider.Usage usage) {
        return new ChatProvider() {
            @Override public CostQuote quote(String user, String previous, String assistant, List<String> keys) {
                return cost;
            }
            @Override public Reply generate(String user, String previous, String assistant, List<String> keys) {
                beforeReply.run();
                return new Reply(List.of(new Utterance("JOY", "같이 시작해 보자.")), usage);
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
