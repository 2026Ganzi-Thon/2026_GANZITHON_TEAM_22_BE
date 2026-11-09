package kr.ganzithon.backend.reminder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ReminderDto {
    private ReminderDto() { }

    public record StartReminder(UUID id, UUID sourceSessionId, OffsetDateTime remindAt,
                                String timeZone, String status, String canceledReason,
                                OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record Current(StartReminder reminder, OffsetDateTime serverTime) { }

    public record TaskCandidate(UUID taskId, LocalDate dueDate, LocalDate reminderDate) { }

    public record TaskCandidates(List<TaskCandidate> items, Integer nextOffset,
                                 OffsetDateTime serverTime) { }
}
