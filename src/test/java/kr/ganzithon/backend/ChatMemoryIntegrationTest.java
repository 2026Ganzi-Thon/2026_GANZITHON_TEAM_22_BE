package kr.ganzithon.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.chat.ChatDto;
import kr.ganzithon.backend.chat.ChatMemoryService;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "ganzi.chat.enabled=false")
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class ChatMemoryIntegrationTest {
    @Autowired ChatMemoryService memory;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    private final List<UUID> owners = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID user : owners) jdbc.update("DELETE FROM users WHERE id=?", user.toString());
    }

    @Test
    void exactReplayDifferentOwnerAndDeletedAccount() {
        UUID owner = user();
        UUID other = user();
        ChatDto.Request request = request("어제 수학 증명 얘기 기억해?", null);
        ChatDto.Exchange exchange = exchange(request, Instant.now().minus(1, ChronoUnit.DAYS), "SUCCEEDED");
        ChatMemoryService.Saved first = memory.save(owner, request, exchange, "a".repeat(64));
        assertEquals(first, memory.save(owner, request, exchange, "a".repeat(64)));
        assertEquals(exchange, memory.find(owner, request.clientRequestId()).orElseThrow().exchange());
        assertTrue(memory.find(other, request.clientRequestId()).isEmpty());
        ApiException reused = assertThrows(ApiException.class,
                () -> memory.save(owner, request, exchange, "b".repeat(64)));
        assertEquals(409, reused.status());
        assertEquals("CLIENT_REQUEST_ID_REUSED", reused.code());
        assertEquals(1, count("SELECT COUNT(*) FROM chat_memory WHERE user_id=?", owner));
        jdbc.update("DELETE FROM users WHERE id=?", owner.toString());
        owners.remove(owner);
        assertEquals(0, count("SELECT COUNT(*) FROM chat_memory WHERE user_id=?", owner));
        assertEquals(0, count("SELECT COUNT(*) FROM chat_memory_terms WHERE user_id=?", owner));
    }

    @Test
    void rawSixtyDaysThenLosslessArchiveAndOldRoomRecall() throws Exception {
        UUID owner = user();
        UUID room = UUID.randomUUID(); // Metadata intentionally does not require a live study room.
        ChatDto.Request old = request("적분 공식이랑 미적분 과제 기억해", room);
        ChatDto.Exchange oldExchange = exchange(old, Instant.now().minus(61, ChronoUnit.DAYS), "SUCCEEDED");
        memory.save(owner, old, oldExchange, "1".repeat(64));
        ChatDto.Request recent = request("물리 문제를 도와줘", null);
        ChatDto.Exchange recentExchange = exchange(recent, Instant.now().minus(59, ChronoUnit.DAYS), "SUCCEEDED");
        memory.save(owner, recent, recentExchange, "2".repeat(64));

        assertEquals(1, memory.archiveOld(100));
        assertNull(jdbc.queryForObject("SELECT raw_payload FROM chat_memory WHERE id=?", String.class, oldExchange.id().toString()));
        assertNotNull(jdbc.queryForObject("SELECT compressed_payload FROM chat_memory WHERE id=?", byte[].class, oldExchange.id().toString()));
        assertNotNull(jdbc.queryForObject("SELECT raw_payload FROM chat_memory WHERE id=?", String.class, recentExchange.id().toString()));
        assertEquals(old, memory.find(owner, old.clientRequestId()).orElseThrow().request());
        assertEquals(oldExchange, memory.find(owner, old.clientRequestId()).orElseThrow().exchange());
        JsonNode context = mapper.readTree(memory.context(owner, "적분 공식 다시 알려줘", null));
        assertTrue(context.toString().contains("적분 공식"));
        assertTrue(context.has("asOf"));
    }

    @Test
    void failedTurnIsLatestButNotQuotedAndHistoryCursorIsStable() throws Exception {
        UUID owner = user();
        UUID room = UUID.randomUUID();
        Instant sameTime = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        ChatDto.Request first = request("첫 대화", room);
        ChatDto.Request second = request("두 번째 대화", room);
        ChatDto.Exchange success = exchange(first, sameTime, "SUCCEEDED");
        ChatDto.Exchange failure = exchange(second, sameTime, "FAILED");
        memory.save(owner, first, success, "3".repeat(64));
        memory.save(owner, second, failure, "4".repeat(64));
        assertEquals(failure.id(), memory.latest(owner, room).orElseThrow().exchange().id());
        assertFalse(memory.context(owner, "두 번째 대화", null).contains("두 번째 대화"));
        ChatMemoryService.HistoryPage page1 = memory.history(owner, 1, null);
        assertEquals(failure.id(), page1.items().get(0).exchange().id());
        assertNotNull(page1.nextCursor());
        ChatMemoryService.HistoryPage page2 = memory.history(owner, 1, page1.nextCursor());
        assertEquals(success.id(), page2.items().get(0).exchange().id());
        assertNull(page2.nextCursor());
        assertEquals(400, assertThrows(ApiException.class, () -> memory.history(owner, 1, "bad!" )).status());
    }

    @Test
    void corruptOldPayloadIsQuarantinedWithoutBlockingOtherUsersOrHealthyHistory() {
        UUID owner = user();
        UUID otherOwner = user();
        Instant now = Instant.now();
        ChatDto.Request oldest = request("기하학 오래된 기록", null);
        ChatDto.Exchange oldestExchange = exchange(oldest, now.minus(63, ChronoUnit.DAYS), "SUCCEEDED");
        memory.save(owner, oldest, oldestExchange, "5".repeat(64));
        ChatDto.Request damaged = request("수학 오염된 기록", null);
        ChatDto.Exchange damagedExchange = exchange(damaged, now.minus(62, ChronoUnit.DAYS), "SUCCEEDED");
        memory.save(owner, damaged, damagedExchange, "6".repeat(64));
        ChatDto.Request other = request("다른 사용자 기록", null);
        ChatDto.Exchange otherExchange = exchange(other, now.minus(61, ChronoUnit.DAYS), "SUCCEEDED");
        memory.save(otherOwner, other, otherExchange, "7".repeat(64));
        ChatDto.Request recent = request("기하학 최근 기록", null);
        ChatDto.Exchange recentExchange = exchange(recent, now.minus(1, ChronoUnit.DAYS), "SUCCEEDED");
        memory.save(owner, recent, recentExchange, "8".repeat(64));
        jdbc.update("UPDATE chat_memory SET raw_payload=? WHERE id=?", "tampered", damagedExchange.id().toString());

        // A newly damaged row need not wait 60 days for the archiver to keep broad reads usable.
        ChatMemoryService.HistoryPage beforeArchive = memory.history(owner, 1, null);
        assertEquals(recentExchange.id(), beforeArchive.items().getFirst().exchange().id());
        assertNotNull(beforeArchive.nextCursor());
        assertTrue(memory.context(owner, "기하학", null).contains("기하학 최근 기록"));

        assertEquals(3, memory.archiveOld(100));
        assertEquals(0, memory.archiveOld(100));
        assertNotNull(jdbc.queryForObject("SELECT detected_at FROM chat_memory_quarantine WHERE memory_id=?", java.sql.Timestamp.class, damagedExchange.id().toString()));
        assertNotNull(jdbc.queryForObject("SELECT compressed_payload FROM chat_memory WHERE id=?", byte[].class, oldestExchange.id().toString()));
        assertNotNull(jdbc.queryForObject("SELECT compressed_payload FROM chat_memory WHERE id=?", byte[].class, otherExchange.id().toString()));
        ChatMemoryService.HistoryPage first = memory.history(owner, 1, null);
        assertEquals(recentExchange.id(), first.items().getFirst().exchange().id());
        assertNotNull(first.nextCursor());
        ChatMemoryService.HistoryPage second = memory.history(owner, 1, first.nextCursor());
        assertEquals(oldestExchange.id(), second.items().getFirst().exchange().id());
        assertNull(second.nextCursor());
        assertTrue(memory.context(owner, "기하학", null).contains("기하학 최근 기록"));
        ApiException error = assertThrows(ApiException.class, () -> memory.find(owner, damaged.clientRequestId()));
        assertEquals("CHAT_HISTORY_UNAVAILABLE", error.code());
        assertEquals(503, error.status());
        assertEquals("tampered", jdbc.queryForObject("SELECT raw_payload FROM chat_memory WHERE id=?", String.class, damagedExchange.id().toString()));
        assertNull(jdbc.queryForObject("SELECT compressed_payload FROM chat_memory WHERE id=?", byte[].class, damagedExchange.id().toString()));
    }

    @Test
    void contextNeverExceedsSixThousandUnicodeCodepoints() {
        UUID owner = user();
        for (int i = 0; i < 10; i++) {
            ChatDto.Request request = request("기하학 " + "한".repeat(800) + i, null);
            ChatDto.Exchange exchange = new ChatDto.Exchange(UUID.randomUUID(), request.clientRequestId(), null,
                    "SUCCEEDED", List.of(new ChatDto.Utterance("angry", "가".repeat(600))), null,
                    Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
            memory.save(owner, request, exchange, Integer.toHexString(i).repeat(64).substring(0, 64));
        }
        String context = memory.context(owner, "기하학", null);
        assertTrue(context.codePointCount(0, context.length()) <= 6_000);
        assertTrue(context.contains("기하학"));
    }

    @Test
    void thirteenNewerFailuresDoNotHideAnEarlierSuccessfulMemory() {
        UUID owner = user();
        Instant base = Instant.now().minus(1, ChronoUnit.DAYS);
        ChatDto.Request remembered = request("방정식 풀이를 기억해 줘", null);
        memory.save(owner, remembered, exchange(remembered, base, "SUCCEEDED"), "6".repeat(64));
        for (int i = 0; i < 13; i++) {
            ChatDto.Request failed = request("실패 " + i, null);
            memory.save(owner, failed, exchange(failed, base.plusSeconds(i + 1), "FAILED"),
                    Integer.toHexString(i).repeat(64).substring(0, 64));
        }
        String context = memory.context(owner, "수학 얘기", null);
        assertTrue(context.contains("방정식 풀이"));
        assertFalse(context.contains("실패 12"));
    }

    @Test
    void olderThanEightTurnsAreRetrievedByExactKoreanTermButParticleVariationIsNot() {
        UUID owner = user();
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        ChatDto.Request exact = request("기하학 증명 목표", null);
        memory.save(owner, exact, exchange(exact, base, "SUCCEEDED"), "9".repeat(64));
        ChatDto.Request inflected = request("토익은 850점이 목표야", null);
        memory.save(owner, inflected, exchange(inflected, base.plusSeconds(1), "SUCCEEDED"), "a".repeat(64));
        for (int i = 0; i < 9; i++) {
            ChatDto.Request newer = request("최근 대화 " + i, null);
            memory.save(owner, newer, exchange(newer, base.plusSeconds(i + 2), "SUCCEEDED"),
                    Integer.toHexString(i).repeat(64).substring(0, 64));
        }
        String exactContext = memory.context(owner, "기하학 증명 기억해?", null);
        assertTrue(exactContext.contains("기하학 증명 목표"));
        String inflectedContext = memory.context(owner, "토익 목표 기억나?", null);
        assertFalse(inflectedContext.contains("850점이"));
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,created_at) VALUES(?,?)", id.toString(), LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        owners.add(id);
        return id;
    }

    private static ChatDto.Request request(String text, UUID room) {
        return new ChatDto.Request(UUID.randomUUID(), text, room, null, List.of("angry"));
    }

    private static ChatDto.Exchange exchange(ChatDto.Request request, Instant finishedAt, String status) {
        return new ChatDto.Exchange(UUID.randomUUID(), request.clientRequestId(), request.studySessionId(), status,
                "SUCCEEDED".equals(status) ? List.of(new ChatDto.Utterance("angry", "응, 기억해!")) : List.of(),
                "FAILED".equals(status) ? "PROVIDER_FAILED" : null, finishedAt.minusSeconds(1), finishedAt);
    }

    private int count(String sql, UUID owner) {
        return jdbc.queryForObject(sql, Integer.class, owner.toString());
    }
}
