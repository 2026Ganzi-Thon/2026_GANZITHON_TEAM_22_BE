package kr.ganzithon.backend.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/history")
public class ChatHistoryController {
    private final ChatMemoryService memory;

    public ChatHistoryController(ChatMemoryService memory) { this.memory = memory; }

    public record Item(UUID clientRequestId, UUID studySessionId, String content, String status,
                       List<ChatDto.Utterance> utterances, String failureCode,
                       Instant createdAt, Instant finishedAt) { }
    public record Page(List<Item> items, String nextCursor) { }

    @GetMapping
    public Page history(@RequestAttribute("userId") UUID userId,
                        @RequestParam(defaultValue = "20") int limit,
                        @RequestParam(required = false) String cursor) {
        if (limit < 1 || limit > 50 || cursor != null && cursor.length() > 256) {
            throw new ApiException(400, "VALIDATION_FAILED", "기록 조회 범위나 커서가 올바르지 않습니다.");
        }
        var page = memory.history(userId, limit, cursor);
        return new Page(page.items().stream().map(saved -> new Item(saved.request().clientRequestId(),
            saved.request().studySessionId(), saved.request().content(), saved.exchange().status(),
            saved.exchange().utterances(), saved.exchange().failureCode(), saved.exchange().createdAt(),
            saved.exchange().finishedAt())).toList(), page.nextCursor());
    }
}
