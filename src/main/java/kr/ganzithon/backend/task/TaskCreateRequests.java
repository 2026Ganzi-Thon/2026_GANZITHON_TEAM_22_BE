package kr.ganzithon.backend.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import kr.ganzithon.backend.common.PortableJdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** A durable record of create requests, including results that were later deleted. */
@Component
final class TaskCreateRequests {
    private final PortableJdbc jdbc;

    TaskCreateRequests(JdbcTemplate jdbc) {
        this.jdbc = new PortableJdbc(jdbc);
    }

    record Result<T>(T value, boolean created) { }

    private record Saved(String type, UUID parentId, UUID resourceId, String hash) { }

    static UUID requestId(JsonNode body) {
        JsonNode node = body.get("clientRequestId");
        if (node == null) return null;
        if (!node.isTextual()) throw TaskService.badRequest("clientRequestId must be a UUID");
        try {
            UUID parsed = UUID.fromString(node.textValue());
            if (!node.textValue().equalsIgnoreCase(parsed.toString())) {
                throw TaskService.badRequest("clientRequestId must be a canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException ex) {
            throw TaskService.badRequest("clientRequestId must be a UUID");
        }
    }

    UUID prior(UUID userId, UUID requestId, String type, UUID parentId, String hash) {
        if (requestId == null) return null;
        List<Saved> records = jdbc.query("SELECT resource_type,parent_task_id,resource_id,request_hash "
                        + "FROM task_create_requests WHERE user_id=? AND request_id=?",
                (rs, row) -> new Saved(rs.getString("resource_type"), PortableJdbc.uuid(rs, "parent_task_id"),
                        PortableJdbc.uuid(rs, "resource_id"), rs.getString("request_hash")), userId, requestId);
        if (records.isEmpty()) return null;
        Saved saved = records.getFirst();
        if (!saved.type().equals(type) || !Objects.equals(saved.parentId(), parentId)
                || !saved.hash().equals(hash)) {
            throw new ApiException(409, "CLIENT_REQUEST_ID_REUSED", "Request ID was used with different input");
        }
        return saved.resourceId();
    }

    void record(UUID userId, UUID requestId, String type, UUID parentId,
                UUID resourceId, String hash, OffsetDateTime now) {
        if (requestId == null) return;
        jdbc.update("INSERT INTO task_create_requests "
                        + "(user_id,request_id,resource_type,parent_task_id,resource_id,request_hash,created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                userId, requestId, type, parentId, resourceId, hash, now);
    }

    static String hash(String type, UUID parentId, String... fields) {
        StringBuilder canonical = new StringBuilder("task-create/v1");
        append(canonical, type);
        append(canonical, parentId == null ? null : parentId.toString());
        for (String field : fields) append(canonical, field);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null) builder.append("|null");
        else builder.append('|').append(value.length()).append(':').append(value);
    }

    static ApiException deleted() {
        return new ApiException(409, "CREATE_RESULT_DELETED", "Created item was deleted after this request");
    }
}
