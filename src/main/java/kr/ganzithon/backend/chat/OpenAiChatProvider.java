package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import kr.ganzithon.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Opt-in, single-call Responses API adapter. Never logs requests or upstream bodies. */
@Component
@ConditionalOnProperty(name = "ganzi.chat.enabled", havingValue = "true")
@ConditionalOnProperty(name = "ganzi.chat.provider", havingValue = "openai")
public final class OpenAiChatProvider implements ChatProvider {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final Set<String> CHARACTER_KEYS = Set.of("ANGRY", "JOY", "SAD");
    private static final Set<String> STUDY_STATES = Set.of("HOME", "STUDYING", "PAUSED");
    private static final Set<String> SUPPORTED_MODELS = Set.of("gpt-6-luna", "gpt-6.1-terra");
    private static final int MAX_REQUEST_BYTES = 65_536;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI uri;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;
    private final int maxOutputTokens;

    @Autowired
    public OpenAiChatProvider(ObjectMapper mapper,
                              @Value("${ganzi.openai.api-key:}") String apiKey,
                              @Value("${ganzi.openai.model:gpt-6-luna}") String model,
                              @Value("${ganzi.openai.timeout-seconds:20}") int timeoutSeconds,
                              @Value("${ganzi.openai.max-output-tokens:1024}") int maxOutputTokens) {
        this(mapper, apiKey, model, timeoutSeconds, maxOutputTokens, RESPONSES_URI,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    // Only tests in this package can route synthetic requests to a loopback server.
    OpenAiChatProvider(ObjectMapper mapper, String apiKey, String model,
                       int timeoutSeconds, int maxOutputTokens, URI uri, HttpClient client) {
        this.mapper = mapper;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputTokens = maxOutputTokens;
        this.uri = uri;
        this.client = client;
    }

    @Override
    public void ensureConfigured() {
        if (apiKey == null || apiKey.isBlank() || !SUPPORTED_MODELS.contains(model)
            || timeoutSeconds < 1 || timeoutSeconds > 30
            || maxOutputTokens < 128 || maxOutputTokens > 2048) {
            throw new ApiException(503, "CHAT_NOT_CONFIGURED", "대화 서비스 설정이 완료되지 않았습니다.");
        }
    }

    @Override
    public CostQuote quote(String userContent, String previousUserContent,
                          String previousAssistantContent, List<String> characterKeys) {
        return quote(userContent, previousUserContent, previousAssistantContent, characterKeys, "HOME");
    }

    @Override
    public CostQuote quote(String userContent, String previousUserContent,
                          String previousAssistantContent, List<String> characterKeys,
                          String studyState) {
        return quote(userContent, previousUserContent, previousAssistantContent, characterKeys, studyState, null);
    }

    @Override
    public CostQuote quote(String userContent, String previousUserContent,
                          String previousAssistantContent, List<String> characterKeys,
                          String studyState, String memoryContext) {
        byte[] body = payload(userContent, previousUserContent, previousAssistantContent,
            characterKeys, studyState, memoryContext);
        // UTF-8 request bytes plus fixed margin conservatively bound token count, including schema.
        // The same payload builder is used by generate; no cache discount is assumed.
        return new CostQuote(model, (long) body.length + 4096L, maxOutputTokens);
    }

    @Override
    public Reply generate(String userContent, String previousUserContent,
                          String previousAssistantContent, List<String> characterKeys) {
        return generate(userContent, previousUserContent, previousAssistantContent, characterKeys, "HOME");
    }

    @Override
    public Reply generate(String userContent, String previousUserContent,
                          String previousAssistantContent, List<String> characterKeys,
                          String studyState) {
        return generate(userContent, previousUserContent, previousAssistantContent, characterKeys, studyState, null);
    }

    @Override
    public Reply generate(String userContent, String previousUserContent,
                          String previousAssistantContent, List<String> characterKeys,
                          String studyState, String memoryContext) {
        byte[] body = payload(userContent, previousUserContent, previousAssistantContent,
            characterKeys, studyState, memoryContext);
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        CompletableFuture<HttpResponse<String>> pending =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = pending.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw httpError(response.statusCode(), response.body());
            return parseSuccess(response.body(), characterKeys);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw failure("AI_PROVIDER_FAILED", true, null);
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw failure("AI_PROVIDER_FAILED", true, null);
        } catch (ExecutionException ex) {
            throw failure("AI_PROVIDER_FAILED", true, null);
        }
    }

    private byte[] payload(String userContent, String previousUserContent,
                           String previousAssistantContent, List<String> characterKeys,
                           String studyState, String memoryContext) {
        ensureConfigured();
        validateInput(userContent, previousUserContent, previousAssistantContent,
            characterKeys, studyState);
        if (memoryContext != null && codePoints(memoryContext) > 6000) {
            throw new ApiException(400, "VALIDATION_FAILED", "과거 대화 문맥이 너무 깁니다.");
        }
        try {
            Map<String, Object> conversation = new LinkedHashMap<>();
            conversation.put("currentUserContent", userContent);
            conversation.put("previousUserContent", previousUserContent == null ? "" : previousUserContent);
            conversation.put("previousAssistantContent", previousAssistantContent == null ? "" : previousAssistantContent);
            conversation.put("characterKeys", characterKeys);
            conversation.put("serverStudyState", studyState);
            conversation.put("memoryContext", memoryContext == null ? "" : memoryContext);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("type", "object");
            line.put("properties", Map.of(
                "characterKey", Map.of("type", "string", "enum", characterKeys),
                "content", Map.of("type", "string")));
            line.put("required", List.of("characterKey", "content"));
            line.put("additionalProperties", false);
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of("utterances", Map.of(
                "type", "array", "items", line, "minItems", 1, "maxItems", 4)));
            schema.put("required", List.of("utterances"));
            schema.put("additionalProperties", false);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("instructions", CharacterPrompt.systemPrompt());
            request.put("input", mapper.writeValueAsString(conversation));
            request.put("store", false);
            request.put("service_tier", "default");
            request.put("reasoning", Map.of("effort", "none"));
            request.put("max_output_tokens", maxOutputTokens);
            request.put("text", Map.of("format", Map.of(
                "type", "json_schema", "name", "character_dialogue",
                "strict", true, "schema", schema)));
            byte[] body = mapper.writeValueAsBytes(request);
            if (body.length > MAX_REQUEST_BYTES) {
                throw new ApiException(400, "VALIDATION_FAILED", "대화 요청이 너무 깁니다.");
            }
            return body;
        } catch (JsonProcessingException ex) {
            throw new ApiException(500, "CHAT_REQUEST_FAILED", "대화 요청을 만들 수 없습니다.");
        }
    }

    private void validateInput(String current, String previousUser, String previousAssistant,
                               List<String> keys, String studyState) {
        if (current == null || current.isBlank() || codePoints(current) > 1000
            || (previousUser != null && codePoints(previousUser) > 1000)
            || (previousAssistant != null && codePoints(previousAssistant) > 2000)
            || keys == null || keys.isEmpty() || keys.size() > 3
            || !CHARACTER_KEYS.containsAll(keys) || new HashSet<>(keys).size() != keys.size()
            || studyState == null || !STUDY_STATES.contains(studyState)) {
            throw new ApiException(400, "VALIDATION_FAILED", "대화 요청 형식이나 값이 올바르지 않습니다.");
        }
    }

    private Reply parseSuccess(String body, List<String> allowed) {
        if (body == null || body.length() > 131_072) throw failure("AI_RESPONSE_INVALID", true, null);
        JsonNode root;
        try { root = mapper.readTree(body); }
        catch (JsonProcessingException ex) { throw failure("AI_RESPONSE_INVALID", true, null); }
        if (root == null || !root.isObject()) throw failure("AI_RESPONSE_INVALID", true, null);
        Usage usage = usage(root.path("usage"));
        boolean unknown = usage == null;
        if (!"completed".equals(root.path("status").asText())) {
            String errorCode = root.path("error").path("code").asText();
            if ("refusal".equals(errorCode)) throw failure("AI_REFUSED", unknown, usage);
            throw failure("AI_RESPONSE_INVALID", unknown, usage);
        }
        JsonNode output = root.path("output");
        if (!output.isArray()) throw failure("AI_RESPONSE_INVALID", unknown, usage);
        String json = null;
        for (JsonNode item : output) {
            String type = item.path("type").asText();
            if ("reasoning".equals(type)) continue;
            if (!"message".equals(type) || !item.path("content").isArray()) {
                throw failure("AI_RESPONSE_INVALID", unknown, usage);
            }
            for (JsonNode block : item.path("content")) {
                if ("refusal".equals(block.path("type").asText())) {
                    throw failure("AI_REFUSED", unknown, usage);
                }
                if (!"output_text".equals(block.path("type").asText())
                    || json != null || !block.path("text").isTextual()) {
                    throw failure("AI_RESPONSE_INVALID", unknown, usage);
                }
                json = block.path("text").asText();
            }
        }
        if (json == null || json.length() > 8192) throw failure("AI_RESPONSE_INVALID", unknown, usage);
        try {
            JsonNode parsed = mapper.readTree(json);
            if (parsed == null || !parsed.isObject() || parsed.size() != 1) {
                throw failure("AI_RESPONSE_INVALID", unknown, usage);
            }
            JsonNode lines = parsed.path("utterances");
            if (!lines.isArray() || lines.isEmpty() || lines.size() > 4) {
                throw failure("AI_RESPONSE_INVALID", unknown, usage);
            }
            List<Utterance> utterances = new ArrayList<>();
            for (JsonNode line : lines) {
                if (!line.isObject() || line.size() != 2
                    || !line.path("characterKey").isTextual() || !line.path("content").isTextual()) {
                    throw failure("AI_RESPONSE_INVALID", unknown, usage);
                }
                String key = line.path("characterKey").asText();
                String content = line.path("content").asText().strip();
                if (!allowed.contains(key) || content.isEmpty() || codePoints(content) > 300) {
                    throw failure("AI_RESPONSE_INVALID", unknown, usage);
                }
                utterances.add(new Utterance(key, content));
            }
            if (usage == null) throw failure("AI_RESPONSE_INVALID", true, null);
            return new Reply(List.copyOf(utterances), usage);
        } catch (JsonProcessingException ex) {
            throw failure("AI_RESPONSE_INVALID", unknown, usage);
        }
    }

    private Usage usage(JsonNode node) {
        if (!node.isObject() || !node.path("input_tokens").isIntegralNumber()
            || !node.path("input_tokens").canConvertToLong()
            || !node.path("output_tokens").isIntegralNumber()
            || !node.path("output_tokens").canConvertToLong()) return null;
        long input = node.path("input_tokens").asLong();
        long output = node.path("output_tokens").asLong();
        JsonNode cachedNode = node.path("input_tokens_details").path("cached_tokens");
        if (!cachedNode.isMissingNode()
            && (!cachedNode.isIntegralNumber() || !cachedNode.canConvertToLong())) return null;
        long cached = cachedNode.isMissingNode() ? 0L : cachedNode.asLong();
        if (input < 0 || output < 0 || cached < 0 || cached > input) return null;
        return new Usage(input, cached, output);
    }

    private ChatProviderException httpError(int status, String body) {
        String code = "";
        String type = "";
        if (body != null && body.length() <= 16_384) {
            try {
                JsonNode error = mapper.readTree(body).path("error");
                code = error.path("code").asText();
                type = error.path("type").asText();
            }
            catch (JsonProcessingException | NullPointerException ignored) { /* Never expose upstream body. */ }
        }
        String mapped;
        if ("credit_balance_exhausted".equals(code)) mapped = "AI_CREDIT_EXHAUSTED";
        else if ("insufficient_quota".equals(code) || "billing_hard_limit_reached".equals(code)
            || "usage_limit_reached".equals(code) || "quota_exceeded".equals(code)
            || "organization_spend_limit_exceeded".equals(code)
            || "project_spend_limit_exceeded".equals(code)
            || "organization_usage_limit_exceeded".equals(code)
            || (code.isEmpty() && "insufficient_quota".equals(type))) {
            mapped = "AI_UPSTREAM_QUOTA_EXCEEDED";
        } else if (status == 429 || "rate_limit_error".equals(type)) mapped = "AI_UPSTREAM_RATE_LIMITED";
        else mapped = "AI_PROVIDER_FAILED";
        return failure(mapped, status >= 500, null);
    }

    private ChatProviderException failure(String code, boolean unknown, Usage usage) {
        return new ChatProviderException(code, unknown, usage);
    }

    private int codePoints(String text) { return text.codePointCount(0, text.length()); }
}
