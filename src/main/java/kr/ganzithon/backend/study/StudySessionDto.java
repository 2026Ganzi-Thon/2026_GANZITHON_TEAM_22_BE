package kr.ganzithon.backend.study;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StudySessionDto(
        UUID id,
        UUID taskId,
        String status,
        int version,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String endReason,
        long studySeconds,
        long priorTaskStudySeconds,
        Long totalTaskStudySeconds,
        OffsetDateTime runningStartedAt,
        UUID startRequestId,
        OffsetDateTime serverTime
) { }
