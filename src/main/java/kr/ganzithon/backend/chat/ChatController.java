package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/exchanges")
public class ChatController {
    private static final Set<String> FIELDS = Set.of("clientRequestId", "content", "studySessionId",
            "previousRequestId", "characterKeys");
    private static final Set<String> CHARACTER_KEYS = Set.of("ANGRY", "JOY", "SAD");
    private final ChatService service;

    public ChatController(ChatService service) { this.service = service; }

    @PostMapping
    public ChatDto.Exchange create(@RequestAttribute("userId") UUID userId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) throw invalid("JSON object required");
        body.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw invalid("Unknown field: " + field);
        });
        UUID requestId = uuid(body.get("clientRequestId"), "clientRequestId", false);
        UUID sessionId = uuid(body.get("studySessionId"), "studySessionId", true);
        UUID previousId = uuid(body.get("previousRequestId"), "previousRequestId", true);
        JsonNode contentNode = body.get("content");
        if (contentNode == null || !contentNode.isTextual()) throw invalid("content must be text");
        String content = contentNode.textValue();
        int codePoints = content.codePointCount(0, content.length());
        if (content.isBlank() || codePoints > 1000) throw invalid("content must contain 1 to 1000 characters");

        List<String> characterKeys = List.of("ANGRY", "JOY", "SAD");
        JsonNode keysNode = body.get("characterKeys");
        if (keysNode != null) {
            if (!keysNode.isArray() || keysNode.size() < 1 || keysNode.size() > 3) {
                throw invalid("characterKeys must contain 1 to 3 keys");
            }
            List<String> parsed = new ArrayList<>();
            Set<String> distinct = new HashSet<>();
            for (JsonNode key : keysNode) {
                if (!key.isTextual() || !CHARACTER_KEYS.contains(key.textValue())
                        || !distinct.add(key.textValue())) {
                    throw invalid("characterKeys must be distinct ANGRY, JOY or SAD keys");
                }
                parsed.add(key.textValue());
            }
            characterKeys = List.copyOf(parsed);
        }
        return service.create(userId, new ChatDto.Request(requestId, content, sessionId, previousId, characterKeys));
    }

    @GetMapping
    public ChatDto.SearchResult recover(@RequestAttribute("userId") UUID userId,
                                        @RequestParam UUID clientRequestId) {
        return service.recover(userId, clientRequestId);
    }

    private static UUID uuid(JsonNode node, String name, boolean optional) {
        if (optional && (node == null || node.isNull())) return null;
        if (node == null || !node.isTextual()) throw invalid(name + " must be a UUID");
        try { return UUID.fromString(node.textValue()); }
        catch (IllegalArgumentException ex) { throw invalid(name + " must be a UUID"); }
    }

    private static ApiException invalid(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }
}
