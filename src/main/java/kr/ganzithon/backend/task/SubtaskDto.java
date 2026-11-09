package kr.ganzithon.backend.task;

import java.time.Instant;
import java.util.UUID;

public record SubtaskDto(
        UUID id,
        UUID taskId,
        String title,
        String status,
        int position,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {}
