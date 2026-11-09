package kr.ganzithon.backend.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record TaskDto(
        UUID id,
        String title,
        String description,
        LocalDate dueDate,
        String status,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt,
        SubtaskSummary subtaskSummary) {

    public record SubtaskSummary(int total, int done, Double ratio) {
        public static SubtaskSummary empty() {
            return new SubtaskSummary(0, 0, null);
        }
    }

    public record Detail(
            UUID id,
            String title,
            String description,
            LocalDate dueDate,
            String status,
            Instant completedAt,
            Instant createdAt,
            Instant updatedAt,
            SubtaskSummary subtaskSummary,
            List<SubtaskDto> subtasks) {
        public static Detail of(TaskDto task, List<SubtaskDto> subtasks) {
            int total = subtasks.size();
            int done = (int) subtasks.stream().filter(item -> "DONE".equals(item.status())).count();
            SubtaskSummary summary = new SubtaskSummary(total, done,
                    total == 0 ? null : (double) done / total);
            return new Detail(task.id(), task.title(), task.description(), task.dueDate(),
                    task.status(), task.completedAt(), task.createdAt(), task.updatedAt(),
                    summary, List.copyOf(subtasks));
        }
    }

    public record TaskList(List<TaskDto> items) {}
}
