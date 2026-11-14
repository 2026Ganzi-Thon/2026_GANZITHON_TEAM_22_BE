package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, user-owned exchange ledger. Compression is lossless and does not expire records. */
@Service
public class ChatMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final int MAX_PAYLOAD_BYTES = 65_536;
    private static final int MAX_CONTEXT_CODEPOINTS = 6_000;
    private static final int MAX_TERMS = 12;
    private static final Duration RAW_RETENTION = Duration.ofDays(60);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,32}");
    private static final Set<String> STOPWORDS = Set.of("그리고", "그런데", "그래서", "오늘", "내일", "제가", "저는", "이거", "이제", "정말", "다시", "지금", "하는", "있어", "없어", "같이", "공부", "과제", "캐릭터");
    private static final String COLUMNS = "id,sequence_id,user_id,client_request_id,study_session_id,status,request_hash,payload_sha256,payload_bytes,raw_payload,compressed_payload,created_at,finished_at,archived_at";

    public record Saved(ChatDto.Request request, ChatDto.Exchange exchange, String requestHash) { }
    public record HistoryPage(List<Saved> items, String nextCursor) { }
    public record Payload(ChatDto.Request request, ChatDto.Exchange exchange) { }
    private record Row(UUID id, long sequenceId, UUID userId, UUID requestId, UUID studySessionId, String status, String requestHash,
                       String sha256, int bytes, String raw, byte[] compressed,
                       Instant createdAt, Instant finishedAt, Instant archivedAt) { }
    private record Cursor(Instant finishedAt, long sequenceId) { }
    /** Only a persisted payload that fails integrity or shape checks is quarantinable. */
    private static final class CorruptPayloadException extends ApiException {
        private CorruptPayloadException() {
            super(503, "CHAT_HISTORY_UNAVAILABLE", "Saved conversation could not be read safely", false);
        }
    }

    private final PortableJdbc jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ChatMemoryService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = new PortableJdbc(jdbc);
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Same request key and fingerprint replay the original result; a changed request conflicts. */
    @Transactional
    public Saved save(UUID userId, ChatDto.Request request, ChatDto.Exchange exchange, String requestHash) {
        if (userId == null || request == null || exchange == null || request.clientRequestId() == null
                || exchange.id() == null || exchange.createdAt() == null || exchange.finishedAt() == null
                || !("SUCCEEDED".equals(exchange.status()) || "FAILED".equals(exchange.status()))
                || requestHash == null || !requestHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("A completed, identified exchange and SHA-256 request hash are required");
        }
        if (!request.clientRequestId().equals(exchange.clientRequestId())) {
            throw new IllegalArgumentException("Request identifiers disagree");
        }
        // Serialize with account deletion so a concurrent deletion cannot orphan the exchange.
        if (jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId).isEmpty()) {
            throw new ApiException(401, "AUTH_REQUIRED", "User is no longer active");
        }
        Optional<Saved> previous = find(userId, request.clientRequestId());
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equalsIgnoreCase(requestHash) || !previous.get().request().equals(request)) {
                throw new ApiException(409, "CLIENT_REQUEST_ID_REUSED", "Request identifier was reused with different content");
            }
            return previous.get();
        }
        byte[] bytes = serialize(new Payload(request, exchange));
        if (bytes.length == 0 || bytes.length > MAX_PAYLOAD_BYTES) {
            throw new ApiException(503, "CHAT_MEMORY_WRITE_FAILED", "Completed conversation exceeded storage limit");
        }
        String hash = sha(bytes);
        jdbc.update("INSERT INTO chat_memory (id,user_id,client_request_id,study_session_id,status,request_hash,payload_sha256,payload_bytes,raw_payload,compressed_payload,created_at,finished_at,archived_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
                exchange.id(), userId, request.clientRequestId(), request.studySessionId(), exchange.status(), requestHash.toLowerCase(Locale.ROOT),
                hash, bytes.length, new String(bytes, StandardCharsets.UTF_8), null, exchange.createdAt(), exchange.finishedAt());
        for (String term : terms(request.content())) {
            jdbc.update("INSERT INTO chat_memory_terms (user_id,term_hash,memory_id) VALUES (?,?,?)", userId, sha(term.getBytes(StandardCharsets.UTF_8)), exchange.id());
        }
        return new Saved(request, exchange, requestHash.toLowerCase(Locale.ROOT));
    }

    public Optional<Saved> find(UUID userId, UUID clientRequestId) {
        if (userId == null || clientRequestId == null) return Optional.empty();
        List<Row> rows = read(() -> jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND client_request_id=?", this::row, userId, clientRequestId));
        return rows.isEmpty() ? Optional.empty() : Optional.of(saved(rows.get(0)));
    }

    /** Most recently finalized exchange in this room; a failed turn still blocks stale previousRequestId. */
    public Optional<Saved> latest(UUID userId, UUID studySessionId) {
        if (userId == null) return Optional.empty();
        List<Row> rows = read(() -> studySessionId == null
                ? jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND study_session_id IS NULL ORDER BY finished_at DESC,sequence_id DESC LIMIT 1", this::row, userId)
                : jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND study_session_id=? ORDER BY finished_at DESC,sequence_id DESC LIMIT 1", this::row, userId, studySessionId));
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(saved(rows.getFirst()));
        } catch (CorruptPayloadException ignored) {
            // A damaged latest turn cannot anchor an explicitly linked request.
            return Optional.empty();
        }
    }

    /** Opaque, stable (timestamp, sequence) seek pagination; every read is scoped by owner. */
    public HistoryPage history(UUID userId, int limit, String cursor) {
        if (userId == null || limit < 1 || limit > 50) {
            throw new ApiException(400, "VALIDATION_FAILED", "Invalid history request");
        }
        Cursor after = parseCursor(cursor);
        List<Row> healthyRows = new ArrayList<>();
        List<Saved> healthy = new ArrayList<>();
        while (healthy.size() <= limit) {
            Cursor pageStart = after;
            List<Row> rows = read(() -> pageStart == null
                    ? jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=chat_memory.id) ORDER BY finished_at DESC,sequence_id DESC LIMIT ?", this::row, userId, limit + 1)
                    : jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=chat_memory.id) AND (finished_at<? OR (finished_at=? AND sequence_id<?)) ORDER BY finished_at DESC,sequence_id DESC LIMIT ?", this::row,
                            userId, pageStart.finishedAt(), pageStart.finishedAt(), pageStart.sequenceId(), limit + 1));
            if (rows.isEmpty()) break;
            for (Row row : rows) {
                after = new Cursor(row.finishedAt(), row.sequenceId());
                try {
                    healthy.add(saved(row));
                    healthyRows.add(row);
                } catch (CorruptPayloadException ignored) {
                    // Omit only a proven damaged payload. Database read errors still propagate.
                }
                if (healthy.size() > limit) break;
            }
            if (healthy.size() > limit || rows.size() < limit + 1) break;
        }
        boolean more = healthy.size() > limit;
        List<Saved> page = more ? healthy.subList(0, limit) : healthy;
        return new HistoryPage(List.copyOf(page), more ? cursor(healthyRows.get(limit - 1)) : null);
    }

    /** A bounded prompt excerpt: recent turns plus up to two older lexical matches. */
    public String context(UUID userId, String currentInput, UUID excludedRequestId) {
        if (userId == null) return contextJson(clock.instant(), List.of());
        List<Row> recent = read(() -> excludedRequestId == null
                ? jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND status='SUCCEEDED' AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=chat_memory.id) ORDER BY finished_at DESC,sequence_id DESC LIMIT 8",
                        this::row, userId)
                : jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND status='SUCCEEDED' AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=chat_memory.id) AND client_request_id<>? ORDER BY finished_at DESC,sequence_id DESC LIMIT 8",
                        this::row, userId, excludedRequestId));
        LinkedHashMap<UUID, Row> selected = new LinkedHashMap<>();
        for (Row row : recent) {
            selected.put(row.id(), row);
        }
        Set<String> clues = terms(currentInput);
        if (!clues.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(clues.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(userId);
            for (String term : clues) args.add(sha(term.getBytes(StandardCharsets.UTF_8)));
            args.add(12);
            List<UUID> matches = read(() -> jdbc.query("SELECT t.memory_id FROM chat_memory_terms t JOIN chat_memory m ON m.user_id=t.user_id AND m.id=t.memory_id WHERE t.user_id=? AND m.status='SUCCEEDED' AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=m.id) AND t.term_hash IN (" + placeholders + ") GROUP BY t.memory_id ORDER BY COUNT(*) DESC,MAX(m.finished_at) DESC,MAX(m.sequence_id) DESC LIMIT ?",
                    (rs, index) -> PortableJdbc.uuid(rs, 1), args.toArray()));
            if (!matches.isEmpty()) {
                String ids = String.join(",", java.util.Collections.nCopies(matches.size(), "?"));
                List<Object> lookup = new ArrayList<>();
                lookup.add(userId);
                lookup.addAll(matches);
                List<Row> older = read(() -> jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE user_id=? AND id IN (" + ids + ") AND status='SUCCEEDED' AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=chat_memory.id)", this::row, lookup.toArray()));
                Map<UUID, Row> byId = new LinkedHashMap<>();
                for (Row row : older) byId.put(row.id(), row);
                int added = 0;
                for (UUID id : matches) {
                    Row row = byId.get(id);
                    if (row != null && !row.requestId().equals(excludedRequestId) && !selected.containsKey(row.id()) && added < 2) {
                        selected.put(row.id(), row);
                        added++;
                    }
                }
            }
        }
        List<Row> ordered = selected.values().stream().sorted(Comparator.comparing(Row::finishedAt).thenComparingLong(Row::sequenceId).reversed()).toList();
        List<Map<String, Object>> excerpts = new ArrayList<>();
        Instant asOf = clock.instant();
        for (Row row : ordered) {
            Saved item;
            try {
                item = saved(row);
            } catch (CorruptPayloadException ignored) {
                // A damaged prior exchange must not block other conversation context.
                continue;
            }
            Map<String, Object> excerpt = new LinkedHashMap<>();
            excerpt.put("finishedAt", row.finishedAt().toString());
            excerpt.put("user", truncate(item.request().content(), 800));
            excerpt.put("characters", item.exchange().utterances().stream().map(u -> Map.of(
                    "characterKey", u.characterKey(), "content", truncate(u.content(), 600))).toList());
            excerpts.add(excerpt);
            String candidate = contextJson(asOf, excerpts);
            if (candidate.codePointCount(0, candidate.length()) > MAX_CONTEXT_CODEPOINTS) excerpts.remove(excerpts.size() - 1);
        }
        return contextJson(asOf, excerpts);
    }

    /** Archives only rows strictly older than 60 days, in bounded, locked batches. */
    @Transactional
    public int archiveOld(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Archive batch must be 1..100");
        Instant cutoff = clock.instant().minus(RAW_RETENTION);
        List<Row> rows = jdbc.query("SELECT " + COLUMNS + " FROM chat_memory WHERE archived_at IS NULL AND NOT EXISTS (SELECT 1 FROM chat_memory_quarantine q WHERE q.memory_id=chat_memory.id) AND finished_at<? ORDER BY finished_at,sequence_id LIMIT ? FOR UPDATE SKIP LOCKED",
                this::row, cutoff, limit);
        int processed = 0;
        for (Row row : rows) {
            byte[] original;
            try {
                // Validate the stored envelope as well as its byte hash before archiving.
                original = verify(row);
                saved(row, original);
            } catch (CorruptPayloadException corrupted) {
                int marked = jdbc.update("INSERT INTO chat_memory_quarantine(memory_id,user_id,detected_at) VALUES(?,?,?)",
                        row.id(), row.userId(), clock.instant());
                if (marked != 1) throw unavailable();
                log.warn("Quarantining damaged chat record {}", row.id());
                processed++;
                continue;
            }
            byte[] compressed = gzip(original);
            if (!MessageDigest.isEqual(original, gunzip(compressed))) {
                throw unavailable();
            }
            int updated = jdbc.update("UPDATE chat_memory SET raw_payload=NULL,compressed_payload=?,archived_at=? WHERE id=? AND user_id=? AND archived_at IS NULL AND payload_sha256=?",
                    compressed, clock.instant(), row.id(), row.userId(), row.sha256());
            if (updated != 1) throw unavailable();
            processed++;
        }
        return processed;
    }

    private Row row(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new Row(PortableJdbc.uuid(rs, "id"), rs.getLong("sequence_id"), PortableJdbc.uuid(rs, "user_id"),
                PortableJdbc.uuid(rs, "client_request_id"), PortableJdbc.uuid(rs, "study_session_id"), rs.getString("status"),
                rs.getString("request_hash"), rs.getString("payload_sha256"), rs.getInt("payload_bytes"),
                rs.getString("raw_payload"), rs.getBytes("compressed_payload"), PortableJdbc.instant(rs, "created_at"),
                PortableJdbc.instant(rs, "finished_at"), PortableJdbc.instant(rs, "archived_at"));
    }

    private Saved saved(Row row) {
        return saved(row, verify(row));
    }

    private Saved saved(Row row, byte[] bytes) {
        try {
            Payload payload = mapper.readValue(bytes, Payload.class);
            if (payload.request() == null || payload.exchange() == null
                    || !row.requestId().equals(payload.request().clientRequestId())
                    || !row.id().equals(payload.exchange().id())
                    || !row.status().equals(payload.exchange().status())) throw new CorruptPayloadException();
            return new Saved(payload.request(), payload.exchange(), row.requestHash());
        } catch (IOException exception) {
            throw new CorruptPayloadException();
        }
    }

    private byte[] verify(Row row) {
        byte[] bytes;
        if (row.raw() != null && row.compressed() == null) bytes = row.raw().getBytes(StandardCharsets.UTF_8);
        else if (row.raw() == null && row.compressed() != null) bytes = gunzip(row.compressed());
        else throw new CorruptPayloadException();
        if (bytes.length != row.bytes() || bytes.length > MAX_PAYLOAD_BYTES || !sha(bytes).equalsIgnoreCase(row.sha256())) throw new CorruptPayloadException();
        return bytes;
    }

    private byte[] serialize(Payload payload) {
        try { return mapper.writeValueAsBytes(payload); }
        catch (IOException exception) { throw new ApiException(503, "CHAT_MEMORY_WRITE_FAILED", "Could not preserve completed conversation"); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException exception) { throw unavailable(); }
    }

    private String contextJson(Instant asOf, List<Map<String, Object>> records) {
        return json(Map.of("asOf", asOf.toString(), "records", records));
    }

    private static String truncate(String text, int maxCodepoints) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= maxCodepoints ? text : text.substring(0, text.offsetByCodePoints(0, maxCodepoints));
    }

    private static Set<String> terms(String input) {
        LinkedHashSet<String> output = new LinkedHashSet<>();
        if (input == null) return output;
        Matcher matcher = WORD.matcher(input.toLowerCase(Locale.ROOT));
        while (matcher.find() && output.size() < MAX_TERMS) {
            String word = matcher.group();
            if (!STOPWORDS.contains(word)) output.add(word);
        }
        return output;
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static byte[] gzip(byte[] input) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(input); }
            return output.toByteArray();
        } catch (IOException exception) { throw unavailable(); }
    }

    private static byte[] gunzip(byte[] input) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096];
            int read;
            while ((read = gzip.read(chunk)) != -1) {
                if (output.size() + read > MAX_PAYLOAD_BYTES) throw new CorruptPayloadException();
                output.write(chunk, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) { throw new CorruptPayloadException(); }
    }

    private static String cursor(Row row) {
        String text = row.finishedAt().toString() + "|" + row.sequenceId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static Cursor parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 160) throw new IllegalArgumentException();
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            long sequence = Long.parseLong(parts[1]);
            if (sequence < 1) throw new IllegalArgumentException();
            return new Cursor(Instant.parse(parts[0]), sequence);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new ApiException(400, "VALIDATION_FAILED", "Invalid history cursor");
        }
    }

    private static ApiException unavailable() {
        return new ApiException(503, "CHAT_HISTORY_UNAVAILABLE", "Conversation history is temporarily unavailable");
    }

    private static <T> T read(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessResourceFailureException | RecoverableDataAccessException | TransientDataAccessException exception) {
            throw unavailable();
        }
    }
}
