package kr.ganzithon.backend.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatDto {
    private ChatDto() { }

    public record Request(UUID clientRequestId, String content, UUID studySessionId,
                          UUID previousRequestId, List<String> characterKeys) { }

    public record Utterance(String characterKey, String content) { }

    public record Exchange(UUID id, UUID clientRequestId, UUID studySessionId, String status,
                           List<Utterance> utterances, String failureCode,
                           Instant createdAt, Instant finishedAt) { }

    public record SearchResult(List<Exchange> items) { }
}
