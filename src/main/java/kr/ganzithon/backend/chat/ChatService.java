package kr.ganzithon.backend.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable user-owned conversation memory with a bounded process-local result cache. */
@Service
public class ChatService {
    private static final Duration RETENTION = Duration.ofMinutes(15);
    private static final int MAX_FRESH_EXCHANGES = 1024;
    private static final int MAX_PER_USER_PER_MINUTE = 10;

    private record Key(UUID userId, UUID requestId) { }
    private final PortableJdbc jdbc;
    private final Clock clock;
    private final ObjectProvider<ChatProvider> provider;
    private final ChatBudgetService budget;
    private final ChatMemoryService memory;
    private final TransactionTemplate transactions;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, Key> inFlight = new HashMap<>();
    private final Duration responseDeadline;
    private long completionOrder;

    private static final class Entry {
        final UUID id = UUID.randomUUID();
        final UUID userId;
        final ChatDto.Request request;
        final Instant createdAt;
        String status = "PROCESSING";
        List<ChatDto.Utterance> utterances = List.of();
        String failureCode;
        Instant finishedAt;
        long completedOrder;

        Entry(UUID userId, ChatDto.Request request, Instant createdAt) {
            this.userId = userId;
            this.request = request;
            this.createdAt = createdAt;
        }

        ChatDto.Exchange view() {
            return new ChatDto.Exchange(id, request.clientRequestId(), request.studySessionId(),
                    status, utterances, failureCode, createdAt, finishedAt);
        }
    }

    @Autowired
    public ChatService(JdbcTemplate jdbc, Clock clock, ObjectProvider<ChatProvider> provider,
                       ChatBudgetService budget, ChatMemoryService memory, TransactionTemplate transactions,
                       @Value("${ganzi.chat.response-deadline-seconds:30}") long responseDeadlineSeconds) {
        this.jdbc = new PortableJdbc(jdbc);
        this.clock = clock;
        this.provider = provider;
        this.budget = budget;
        this.memory = memory;
        this.transactions = transactions;
        if (responseDeadlineSeconds < 1 || responseDeadlineSeconds > 120) {
            throw new IllegalArgumentException("Chat response deadline must be 1 to 120 seconds");
        }
        this.responseDeadline = Duration.ofSeconds(responseDeadlineSeconds);
    }

    // Existing unmetered test providers do not create external paid requests.
    ChatService(JdbcTemplate jdbc, Clock clock, ObjectProvider<ChatProvider> provider, long deadlineSeconds) {
        this(jdbc, clock, provider, null, null, null, deadlineSeconds);
    }

    ChatService(JdbcTemplate jdbc, Clock clock, ObjectProvider<ChatProvider> provider,
                ChatBudgetService budget, long deadlineSeconds) {
        this(jdbc, clock, provider, budget, null, null, deadlineSeconds);
    }

    public ChatDto.Exchange create(UUID userId, ChatDto.Request request) {
        Key key = new Key(userId, request.clientRequestId());
        ChatProvider model;
        Entry entry;
        String previousUserContent = null;
        String previousAssistantContent = null;
        String memoryContext = null;
        String studyState;
        synchronized (this) {
            requireOwner(userId);
            evictExpired(clock.instant());
            Entry old = entries.get(key);
            if (old != null) {
                if (!old.request.equals(request)) {
                    throw new ApiException(409, "CLIENT_REQUEST_ID_REUSED", "This request ID was used with different input");
                }
                if ("PROCESSING".equals(old.status)) {
                    throw new ApiException(409, "REQUEST_IN_PROGRESS", "This exchange is still processing");
                }
                return old.view();
            }
            if (memory != null) {
                var durable = memory.find(userId, request.clientRequestId());
                if (durable.isPresent()) {
                    var saved = durable.get();
                    if (!saved.requestHash().equals(fingerprint(request)) || !saved.request().equals(request)) {
                        throw new ApiException(409, "CLIENT_REQUEST_ID_REUSED", "This request ID was used with different input");
                    }
                    return saved.exchange();
                }
            }
            studyState = requireActiveRoom(userId, request.studySessionId());
            if (inFlight.containsKey(userId)) {
                throw new ApiException(409, "CHAT_IN_PROGRESS", "Another exchange is still processing");
            }
            if (request.previousRequestId() != null) {
                Entry previous = immediatelyPrevious(userId, request.studySessionId());
                if (previous != null && "CHAT_MEMORY_WRITE_FAILED".equals(previous.failureCode)) {
                    throw new ApiException(409, "INVALID_PREVIOUS_EXCHANGE", "Latest exchange was not retained");
                }
                var durablePrevious = memory == null ? null : memory.latest(userId, request.studySessionId()).orElse(null);
                ChatDto.Request previousRequest = previous != null ? previous.request
                    : durablePrevious == null ? null : durablePrevious.request();
                ChatDto.Exchange previousView = previous != null ? previous.view()
                    : durablePrevious == null ? null : durablePrevious.exchange();
                if (previousRequest == null || !previousRequest.clientRequestId().equals(request.previousRequestId())
                        || !"SUCCEEDED".equals(previousView.status())) {
                    throw new ApiException(409, "INVALID_PREVIOUS_EXCHANGE",
                            "Previous exchange must be the latest completed exchange in the same context");
                }
                previousUserContent = previousRequest.content();
                previousAssistantContent = previousView.utterances().stream()
                        .map(line -> line.characterKey() + ": " + line.content())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
            }
            model = provider.getIfAvailable();
            if (model == null) {
                throw new ApiException(503, "CHAT_NOT_CONFIGURED", "AI chat is not configured");
            }
            model.ensureConfigured();
            if (memory != null) {
                memoryContext = memory.context(userId, request.content(), request.previousRequestId());
            }
            long recent = entries.values().stream()
                    .filter(item -> item.userId.equals(userId)
                            && item.createdAt.isAfter(clock.instant().minusSeconds(60)))
                    .count();
            if (recent >= MAX_PER_USER_PER_MINUTE) {
                throw new ApiException(429, "CHAT_RATE_LIMITED", "Too many chat exchanges");
            }
            if (entries.size() >= MAX_FRESH_EXCHANGES) {
                throw new ApiException(503, "CHAT_CAPACITY_REACHED", "Temporary chat capacity reached");
            }
            entry = new Entry(userId, request, clock.instant());
            entries.put(key, entry);
            inFlight.put(userId, key);
        }

        List<ChatDto.Utterance> utterances = List.of();
        String failureCode = null;
        ChatBudgetService.Reservation reservation = null;
        ChatProvider.Usage usage = null;
        boolean chargeableUnknown = false;
        try {
            ChatProvider.CostQuote quote = model.quote(request.content(), previousUserContent,
                    previousAssistantContent, request.characterKeys(), studyState, memoryContext);
            if (quote != null) {
                if (budget == null) throw new IllegalStateException("Missing AI budget service");
                reservation = budget.reserve(userId, request.clientRequestId(), fingerprint(request), quote);
                // From this point an interrupted call may still be billed upstream.
                chargeableUnknown = true;
            }
            ChatProvider.Reply reply = model.generate(request.content(), previousUserContent,
                    previousAssistantContent, request.characterKeys(), studyState, memoryContext);
            usage = reply.usage();
            utterances = validated(reply, request.characterKeys());
        } catch (ChatProviderException ex) {
            failureCode = ex.code();
            usage = ex.usage();
            chargeableUnknown = ex.chargeableUnknown();
        } catch (ApiException ex) {
            if (List.of("CLIENT_REQUEST_ID_REUSED", "REQUEST_IN_PROGRESS", "CHAT_RESULT_UNAVAILABLE").contains(ex.code())) {
                synchronized (this) {
                    entries.remove(key, entry);
                    inFlight.remove(userId, key);
                }
                if (!"CHAT_RESULT_UNAVAILABLE".equals(ex.code())) throw ex;
                requireOwner(userId);
                // Another process can finish between the first memory lookup and admission.
                var saved = memory == null ? null : memory.find(userId, request.clientRequestId()).orElse(null);
                if (saved != null) return saved.exchange();
                // An abandoned request must not create a failed history row that shadows a late result.
                return failed(entry, ex.code());
            }
            failureCode = ex.code();
        } catch (RuntimeException ex) {
            // Do not reveal provider messages or prompt contents to the mobile client.
            failureCode = "AI_PROVIDER_FAILED";
        }

        // Account deletion may have happened while the external provider was running.
        boolean stillOwned = userExists(userId);
        boolean roomStillActive = request.studySessionId() == null || activeRoomExists(userId, request.studySessionId());
        synchronized (this) {
            inFlight.remove(userId, key);
            if (entries.get(key) != entry || !stillOwned) {
                entries.remove(key, entry);
                settleDeletedAccount(reservation, usage, chargeableUnknown, failureCode);
                throw new ApiException(401, "AUTH_REQUIRED", "Account is no longer available");
            }
            if (failureCode == null && !roomStillActive) failureCode = "STUDY_SESSION_ENDED";
            if (failureCode == null && clock.instant().isAfter(entry.createdAt.plus(responseDeadline))) {
                failureCode = "CHAT_DEADLINE_EXCEEDED";
            }
            entry.utterances = failureCode == null ? utterances : List.of();
            entry.failureCode = failureCode;
            entry.status = failureCode == null ? "SUCCEEDED" : "FAILED";
            entry.finishedAt = clock.instant();
            entry.completedOrder = ++completionOrder;
            try {
                ChatDto.Exchange result = persistResult(userId, entry, reservation, usage, chargeableUnknown);
                if (!result.id().equals(entry.id)) entries.remove(key, entry);
                return result;
            } catch (ApiException ex) {
                entries.remove(key, entry);
                if (ex.status() == 401) {
                    settleDeletedAccount(reservation, usage, chargeableUnknown, failureCode);
                    throw ex;
                }
                return failed(entry, "AI_USAGE_RECORD_FAILED".equals(ex.code()) ? ex.code() : "CHAT_MEMORY_WRITE_FAILED");
            } catch (RuntimeException ex) {
                entries.remove(key, entry);
                return failed(entry, "CHAT_MEMORY_WRITE_FAILED");
            }
        }
    }

    private ChatDto.Exchange persistResult(UUID userId, Entry entry, ChatBudgetService.Reservation reservation,
                                            ChatProvider.Usage usage, boolean chargeableUnknown) {
        if (memory == null) {
            settleUsage(reservation, usage, chargeableUnknown, entry.failureCode);
            return entry.view();
        }
        String requestHash = fingerprint(entry.request);
        if (reservation == null) return memory.save(userId, entry.request, entry.view(), requestHash).exchange();
        return transactions.execute(status -> {
            // Saving locks the owner before settlement locks billing, in the same order as admission.
            var saved = memory.save(userId, entry.request, entry.view(), requestHash);
            settleUsage(reservation, usage, chargeableUnknown, entry.failureCode);
            return saved.exchange();
        });
    }

    private void settleUsage(ChatBudgetService.Reservation reservation, ChatProvider.Usage usage,
                             boolean chargeableUnknown, String failureCode) {
        if (reservation == null) return;
        try {
            budget.settle(reservation, usage, chargeableUnknown, failureCode);
        } catch (RuntimeException ex) {
            throw new ApiException(503, "AI_USAGE_RECORD_FAILED", "AI usage could not be recorded");
        }
    }

    private void settleDeletedAccount(ChatBudgetService.Reservation reservation, ChatProvider.Usage usage,
                                     boolean chargeableUnknown, String failureCode) {
        try {
            settleUsage(reservation, usage, chargeableUnknown, failureCode);
        } catch (ApiException ex) {
            // The abandoned reservation is still available for conservative settlement.
        }
    }

    private ChatDto.Exchange failed(Entry entry, String code) {
        return new ChatDto.Exchange(entry.id, entry.request.clientRequestId(), entry.request.studySessionId(),
                "FAILED", List.of(), code, entry.createdAt, clock.instant());
    }

    private static String fingerprint(ChatDto.Request request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of(request.content(), Objects.toString(request.studySessionId(), ""),
                    Objects.toString(request.previousRequestId(), ""), String.join(",", request.characterKeys()))) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public ChatDto.SearchResult recover(UUID userId, UUID clientRequestId) {
        synchronized (this) {
            requireOwner(userId);
            evictExpired(clock.instant());
            Entry entry = entries.get(new Key(userId, clientRequestId));
            if (entry != null) return new ChatDto.SearchResult(List.of(entry.view()));
            return new ChatDto.SearchResult(memory == null ? List.of()
                : memory.find(userId, clientRequestId).map(saved -> List.of(saved.exchange())).orElse(List.of()));
        }
    }

    /** IdentityService should call this after account deletion commits. */
    public synchronized void forgetUser(UUID userId) {
        entries.keySet().removeIf(key -> key.userId().equals(userId));
        inFlight.remove(userId);
    }

    private Entry immediatelyPrevious(UUID userId, UUID studySessionId) {
        Entry latest = null;
        for (Entry item : entries.values()) {
            if (!item.userId.equals(userId) || !Objects.equals(item.request.studySessionId(), studySessionId)
                    || item.finishedAt == null) continue;
            if (latest == null || item.completedOrder > latest.completedOrder) {
                latest = item;
            }
        }
        return latest;
    }

    @Scheduled(fixedDelay = 60_000)
    public synchronized void evictExpiredExchanges() {
        evictExpired(clock.instant());
    }

    private void evictExpired(Instant now) {
        entries.entrySet().removeIf(item -> item.getValue().finishedAt != null
                && !item.getValue().finishedAt.plus(RETENTION).isAfter(now));
    }

    private List<ChatDto.Utterance> validated(ChatProvider.Reply reply, List<String> allowed) {
        if (reply == null || reply.utterances() == null || reply.utterances().isEmpty()
                || reply.utterances().size() > 4) throw new IllegalStateException("Invalid provider reply");
        List<ChatDto.Utterance> lines = new ArrayList<>();
        for (ChatProvider.Utterance line : reply.utterances()) {
            if (line == null || !allowed.contains(line.characterKey()) || line.content() == null
                    || line.content().isBlank() || line.content().codePointCount(0, line.content().length()) > 300) {
                throw new IllegalStateException("Invalid provider utterance");
            }
            lines.add(new ChatDto.Utterance(line.characterKey(), line.content()));
        }
        return List.copyOf(lines);
    }

    private void requireOwner(UUID userId) {
        if (!userExists(userId)) throw new ApiException(401, "AUTH_REQUIRED", "Unknown user");
    }

    private boolean userExists(UUID userId) {
        return !jdbc.queryForList("SELECT id FROM users WHERE id = ?", UUID.class, userId).isEmpty();
    }

    private String requireActiveRoom(UUID userId, UUID studySessionId) {
        if (studySessionId == null) return "HOME";
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM study_sessions WHERE id = ? AND user_id = ?", String.class,
                studySessionId, userId);
        if (statuses.isEmpty()) throw new ApiException(404, "RESOURCE_NOT_FOUND", "Study session not found");
        if (!List.of("STUDYING", "PAUSED").contains(statuses.getFirst())) {
            throw new ApiException(409, "INVALID_STUDY_STATE", "Study session has ended");
        }
        return statuses.getFirst();
    }

    private boolean activeRoomExists(UUID userId, UUID studySessionId) {
        return !jdbc.queryForList("SELECT id FROM study_sessions WHERE id = ? AND user_id = ? "
                        + "AND status IN ('STUDYING', 'PAUSED')", UUID.class, studySessionId, userId).isEmpty();
    }
}
