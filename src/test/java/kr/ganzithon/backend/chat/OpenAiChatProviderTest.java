package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiChatProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> keys = List.of("JOY", "ANGRY", "SAD");

    @Test
    void cacheWritesAreSeparatedFromReadsAndInvalidTotalsAreRejected() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            int call = calls.incrementAndGet();
            String details = call == 1
                ? "{\"cached_tokens\":30,\"cache_write_tokens\":50}"
                : "{\"cached_tokens\":80,\"cache_write_tokens\":50}";
            respond(exchange, 200, "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"utterances\\\":[{\\\"characterKey\\\":\\\"JOY\\\",\\\"content\\\":\\\"안녕\\\"}]}\"}]}],\"usage\":{\"input_tokens\":100,\"input_tokens_details\":"
                + details + ",\"output_tokens\":8}}");
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2, "gpt-6.2-sol", 2048);
            assertEquals(new ChatProvider.Usage(100, 30, 8, 50),
                provider.generate("안녕", null, null, List.of("JOY")).usage());
            ChatProviderException invalid = assertThrows(ChatProviderException.class, () ->
                provider.generate("안녕", null, null, List.of("JOY")));
            assertEquals("AI_RESPONSE_INVALID", invalid.code());
            assertTrue(invalid.chargeableUnknown());
        } finally { server.stop(0); }
    }


    @Test
    void sol62UsesFixedDeveloperBoundaryAndChronologicalTranscript() throws Exception {
        HttpServer server = server();
        AtomicReference<byte[]> sent = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            sent.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, response("completed", """
                {"utterances":[{"characterKey":"SAD","content":"그때 이야기 기억나."}]}
                """));
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2, "gpt-6.2-sol", 2048);
            var history = List.of(
                new ChatProvider.TranscriptTurn(1, "과제 끝낼 거야", List.of(
                    new ChatProvider.Utterance("JOY", "같이 있자."))),
                new ChatProvider.TranscriptTurn(2, "잠깐 쉴래", List.of(
                    new ChatProvider.Utterance("SAD", "쉬어도 돼."))));
            var turn = new ChatProvider.Turn("아까 뭐라고 했지?", null, null,
                List.of("SAD", "JOY"), "PAUSED", "오래된 기억", false, history, 3);
            ChatProvider.CostQuote quote = provider.quote(turn);
            provider.generate(turn);
            assertEquals(sent.get().length + 4096L, quote.maxInputTokens());
            JsonNode request = mapper.readTree(sent.get());
            assertEquals("gpt-6.2-sol", request.path("model").asText());
            assertFalse(request.has("instructions"));
            assertFalse(request.path("store").asBoolean());
            assertEquals("medium", request.path("reasoning").path("effort").asText());
            assertEquals("standard", request.path("reasoning").path("mode").asText());
            assertEquals("explicit", request.path("prompt_cache_options").path("mode").asText());
            assertEquals("30m", request.path("prompt_cache_options").path("ttl").asText());
            assertEquals("haeboyang-chat-transcript-v1-group-JOY-SAD",
                request.path("prompt_cache_key").asText());
            JsonNode developer = request.path("input").get(0);
            assertEquals("developer", developer.path("role").asText());
            assertEquals("input_text", developer.path("content").get(0).path("type").asText());
            assertEquals("explicit", developer.path("content").get(0)
                .path("prompt_cache_breakpoint").path("mode").asText());
            String prompt = developer.path("content").get(0).path("text").asText();
            assertTrue(prompt.startsWith(CharacterPrompt.systemPrompt() + "\n"));
            assertTrue(prompt.endsWith("이어지는 발언에서는 이미 나온 설명을 반복하지 마라."));
            assertFalse(prompt.contains("아까 뭐라고 했지?"));
            JsonNode user = request.path("input").get(1);
            assertEquals("user", user.path("role").asText());
            JsonNode context = mapper.readTree(user.path("content").asText());
            assertEquals(3, context.path("currentTurn").asInt());
            assertEquals("아까 뭐라고 했지?", context.path("currentUserContent").asText());
            assertEquals("PAUSED", context.path("serverStudyState").asText());
            assertEquals("오래된 기억", context.path("memoryContext").asText());
            assertEquals(2, context.path("sessionTranscript").size());
            assertEquals("과제 끝낼 거야", context.path("sessionTranscript").get(0).path("user").asText());
            assertEquals("JOY", context.path("sessionTranscript").get(0).path("replies")
                .get(0).path("characterKey").asText());
            assertEquals("잠깐 쉴래", context.path("sessionTranscript").get(1).path("user").asText());
            assertFalse(context.has("previousUserContent"));
            assertFalse(context.has("previousAssistantContent"));
            provider.generate(new ChatProvider.Turn("처음 왔어", null, null,
                List.of("SAD", "JOY"), "HOME", null, false));
            JsonNode fresh = mapper.readTree(mapper.readTree(sent.get()).path("input")
                .get(1).path("content").asText());
            assertFalse(fresh.has("memoryContext"));
            assertEquals(0, fresh.path("sessionTranscript").size());
            assertEquals(1, fresh.path("currentTurn").asInt());
        } finally { server.stop(0); }
    }


    @Test
    void transcriptCapabilityAndEightFullKoreanTurnsFitRequest() throws Exception {
        HttpServer server = server();
        OpenAiChatProvider sol62 = provider(server, 2, "gpt-6.2-sol", 2048);
        assertTrue(sol62.usesSessionTranscript());
        assertFalse(provider(server, 2, "gpt-6.1-sol", 2048).usesSessionTranscript());
        List<ChatProvider.TranscriptTurn> history = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            history.add(new ChatProvider.TranscriptTurn(i, "가".repeat(1000), List.of(
                new ChatProvider.Utterance("JOY", "나".repeat(300)),
                new ChatProvider.Utterance("ANGRY", "다".repeat(300)),
                new ChatProvider.Utterance("SAD", "라".repeat(300)),
                new ChatProvider.Utterance("JOY", "마".repeat(300)))));
        }
        var turn = new ChatProvider.Turn("바".repeat(1000), null, null, keys,
            "HOME", "사".repeat(6000), false, history, 9);
        assertTrue(sol62.quote(turn).maxInputTokens() > 65_536L);
    }


    @Test
    void focusedSolRequestUsesMediumThinkingAndQuotesThePayloadThatIsSent() throws Exception {
        HttpServer server = server();
        AtomicReference<byte[]> sent = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            sent.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, response("completed", """
                {"utterances":[{"characterKey":"ANGRY","content":"그건 정말 속상했겠다."}]}
                """));
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2, "gpt-6.1-sol", 2048);
            ChatProvider.Turn turn = new ChatProvider.Turn("너무 억울해", null, null,
                List.of("ANGRY"), "HOME", "어제 속상했던 일", true);
            ChatProvider.CostQuote quote = provider.quote(turn);
            ChatProvider.Reply reply = provider.generate(turn);
            assertEquals(1, calls.get());
            assertEquals("gpt-6.1-sol", quote.model());
            assertEquals(sent.get().length + 4096L, quote.maxInputTokens());
            assertEquals(2048, quote.maxOutputTokens());
            assertEquals("ANGRY", reply.utterances().getFirst().characterKey());
            JsonNode request = mapper.readTree(sent.get());
            assertEquals("gpt-6.1-sol", request.path("model").asText());
            assertEquals("standard", request.path("reasoning").path("mode").asText());
            assertEquals("medium", request.path("reasoning").path("effort").asText());
            assertEquals(2048, request.path("max_output_tokens").asInt());
            String instructions = request.path("instructions").asText();
            assertTrue(instructions.contains("화남이는 직설적"));
            assertFalse(instructions.contains("기쁨이는 자신감"));
            assertFalse(instructions.contains("슬픔이는 감수성"));
            assertEquals("어제 속상했던 일", mapper.readTree(request.path("input").asText())
                .path("memoryContext").asText());
            assertEquals("ANGRY", request.path("text").path("format").path("schema")
                .path("properties").path("utterances").path("items").path("properties")
                .path("characterKey").path("enum").get(0).asText());
        } finally { server.stop(0); }
    }

    @Test
    void lunaAndTerraCanUseExplicitMediumThinking() throws Exception {
        HttpServer server = server();
        AtomicReference<byte[]> sent = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            sent.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, response("completed", """
                {"utterances":[{"characterKey":"JOY","content":"함께 해보자."}]}
                """));
        });
        server.start();
        try {
            for (String model : List.of("gpt-6-luna", "gpt-6.1-terra")) {
                OpenAiChatProvider provider = provider(server, 2, model, 1024, "medium");
                ChatProvider.Turn turn = new ChatProvider.Turn("공부 시작할래", null, null,
                    List.of("JOY"), "HOME", null, true);
                ChatProvider.CostQuote quote = provider.quote(turn);
                provider.generate(turn);
                JsonNode request = mapper.readTree(sent.get());
                assertEquals(model, request.path("model").asText());
                assertEquals("standard", request.path("reasoning").path("mode").asText());
                assertEquals("medium", request.path("reasoning").path("effort").asText());
                assertEquals(1024, request.path("max_output_tokens").asInt());
                assertEquals(sent.get().length + 4096L, quote.maxInputTokens());
            }
        } finally { server.stop(0); }
    }

    @Test
    void reasoningEffortMustBeSupportedByModel() throws Exception {
        HttpServer server = server();
        for (String model : List.of("gpt-6.1-sol", "gpt-6-luna", "gpt-6.1-terra")) {
            assertEquals("CHAT_NOT_CONFIGURED", assertThrows(ApiException.class,
                () -> provider(server, 2, model, 1024, "minimal").ensureConfigured()).code());
        }
        assertEquals("CHAT_NOT_CONFIGURED", assertThrows(ApiException.class,
            () -> provider(server, 2, "gpt-6.1-sol", 1024, "none").ensureConfigured()).code());
        for (String model : List.of("gpt-6.1-sol", "gpt-6-luna", "gpt-6.1-terra")) {
            provider(server, 2, model, 1024, "xhigh").ensureConfigured();
        }
    }

    @Test
    void quoteMatchesSentPayloadAndFourOrderedLinesIncludeUsage() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<byte[]> sent = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            calls.incrementAndGet();
            sent.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, 200, response("completed", """
                {"utterances":[{"characterKey":"JOY","content":"같이 시작하자."},
                {"characterKey":"ANGRY","content":"한 쪽만 해보자."},
                {"characterKey":"SAD","content":"천천히 해도 돼."},
                {"characterKey":"JOY","content":"옆에 있을게."}]}
                """));
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2);
            String memory = "{\"asOf\":\"2026-11-08T00:00:00Z\",\"records\":[{\"user\":\"토익 목표 850점\"}]}";
            ChatProvider.CostQuote quote = provider.quote("책을 펴기 어려워", null, null, keys, "PAUSED", memory);
            ChatProvider.Reply reply = provider.generate("책을 펴기 어려워", null, null, keys, "PAUSED", memory);
            assertEquals(1, calls.get());
            assertEquals("gpt-6-luna", quote.model());
            assertEquals(sent.get().length + 4096L, quote.maxInputTokens());
            assertEquals(1024, quote.maxOutputTokens());
            JsonNode request = mapper.readTree(sent.get());
            assertEquals("gpt-6-luna", request.path("model").asText());
            assertFalse(request.path("store").asBoolean());
            assertEquals("default", request.path("service_tier").asText());
            assertEquals("none", request.path("reasoning").path("effort").asText());
            assertTrue(request.path("text").path("format").path("strict").asBoolean());
            assertEquals("PAUSED", mapper.readTree(request.path("input").asText())
                .path("serverStudyState").asText());
            assertEquals(memory, mapper.readTree(request.path("input").asText()).path("memoryContext").asText());
            assertTrue(quote.maxInputTokens() > provider.quote("책을 펴기 어려워", null, null, keys, "PAUSED").maxInputTokens());
            assertTrue(request.path("instructions").asText().contains("serverStudyState"));
            assertTrue(request.path("instructions").asText().contains("이 상태를 우선"));
            assertEquals(List.of("JOY", "ANGRY", "SAD", "JOY"),
                reply.utterances().stream().map(ChatProvider.Utterance::characterKey).toList());
            assertEquals(new ChatProvider.Usage(500, 100, 80), reply.usage());
        } finally { server.stop(0); }
    }

    @Test
    void incompleteAndRefusalRetainKnownUsage() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            int call = calls.incrementAndGet();
            String body = call == 1
                ? """
                  {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                   "usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":0},"output_tokens":1024}}
                  """
                : """
                  {"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}],
                   "usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":0},"output_tokens":4}}
                  """;
            respond(exchange, 200, body);
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2);
            ChatProviderException incomplete = assertThrows(ChatProviderException.class, () ->
                provider.generate("안녕", null, null, keys));
            assertEquals("AI_RESPONSE_INVALID", incomplete.code());
            assertEquals(new ChatProvider.Usage(12, 0, 1024), incomplete.usage());
            assertFalse(incomplete.chargeableUnknown());
            ChatProviderException refused = assertThrows(ChatProviderException.class, () ->
                provider.generate("안녕", null, null, keys));
            assertEquals("AI_REFUSED", refused.code());
            assertEquals(new ChatProvider.Usage(12, 0, 4), refused.usage());
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void billingLimitsAndRateErrorsAreDistinctAndNeverRetried() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        String[] errors = {
            "{\"error\":{\"code\":\"credit_balance_exhausted\"}}",
            "{\"error\":{\"code\":\"organization_spend_limit_exceeded\"}}",
            "{\"error\":{\"code\":\"project_spend_limit_exceeded\"}}",
            "{\"error\":{\"code\":\"organization_usage_limit_exceeded\"}}",
            "{\"error\":{\"code\":\"rate_limit_exceeded\"}}",
            "{\"error\":{\"type\":\"rate_limit_error\"}}"
        };
        server.createContext("/v1/responses", exchange -> {
            int call = calls.incrementAndGet();
            respond(exchange, 429, errors[call - 1]);
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2);
            ChatProviderException credit = assertThrows(ChatProviderException.class, () ->
                provider.generate("안녕", null, null, keys));
            assertEquals("AI_CREDIT_EXHAUSTED", credit.code());
            assertFalse(credit.chargeableUnknown());
            for (int i = 0; i < 3; i++) {
                ChatProviderException quota = assertThrows(ChatProviderException.class, () ->
                    provider.generate("안녕", null, null, keys));
                assertEquals("AI_UPSTREAM_QUOTA_EXCEEDED", quota.code());
                assertFalse(quota.chargeableUnknown());
            }
            for (int i = 0; i < 2; i++) {
                ChatProviderException rate = assertThrows(ChatProviderException.class, () ->
                    provider.generate("안녕", null, null, keys));
                assertEquals("AI_UPSTREAM_RATE_LIMITED", rate.code());
            }
            assertEquals(6, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void fractionalOrMalformedUsageRemainsChargeUnknown() throws Exception {
        HttpServer server = server();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/v1/responses", exchange -> {
            int call = calls.incrementAndGet();
            respond(exchange, 200, responseWithUsage(call == 1 ? 12.5 : 12,
                call == 1 ? 0 : "bad"));
        });
        server.start();
        try {
            OpenAiChatProvider provider = provider(server, 2);
            for (int i = 0; i < 2; i++) {
                ChatProviderException invalid = assertThrows(ChatProviderException.class, () ->
                    provider.generate("안녕", null, null, keys));
                assertEquals("AI_RESPONSE_INVALID", invalid.code());
                assertTrue(invalid.chargeableUnknown());
                assertEquals(null, invalid.usage());
            }
            assertEquals(2, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void bodyDelayIsBoundedAndChargeUncertain() throws Exception {
        HttpServer server = server();
        server.createContext("/v1/responses", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                Thread.sleep(1800);
                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { /* The client closes the fake exchange on timeout. */ }
            finally { exchange.close(); }
        });
        server.start();
        try {
            long start = System.nanoTime();
            ChatProviderException ex = assertThrows(ChatProviderException.class, () ->
                provider(server, 1).generate("안녕", null, null, keys));
            assertEquals("AI_PROVIDER_FAILED", ex.code());
            assertTrue(ex.chargeableUnknown());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1700);
        } finally { server.stop(0); }
    }

    @Test
    void legacyHomeDefaultAndInvalidServerState() throws Exception {
        HttpServer server = server();
        try {
            OpenAiChatProvider provider = provider(server, 2);
            assertEquals(provider.quote("안녕", null, null, keys, "HOME"),
                provider.quote("안녕", null, null, keys));
            ApiException invalid = assertThrows(ApiException.class, () ->
                provider.quote("안녕", null, null, keys, "ENDED"));
            assertEquals(400, invalid.status());
        } finally { server.stop(0); }
    }

    private String response(String status, String text) throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.writeValueAsString(Map.of(
            "status", status,
            "output", List.of(Map.of("type", "message", "content",
                List.of(Map.of("type", "output_text", "text", text)))),
            "usage", Map.of("input_tokens", 500, "input_tokens_details", Map.of("cached_tokens", 100),
                "output_tokens", 80)));
    }

    private String responseWithUsage(Object input, Object cached)
        throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.writeValueAsString(Map.of(
            "status", "completed",
            "output", List.of(Map.of("type", "message", "content", List.of(Map.of(
                "type", "output_text", "text", "{\"utterances\":[{\"characterKey\":\"JOY\",\"content\":\"안녕\"}]}")))),
            "usage", Map.of("input_tokens", input,
                "input_tokens_details", Map.of("cached_tokens", cached), "output_tokens", 8)));
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    }

    private OpenAiChatProvider provider(HttpServer server, int timeoutSeconds) {
        return provider(server, timeoutSeconds, "gpt-6-luna", 1024);
    }

    private OpenAiChatProvider provider(HttpServer server, int timeoutSeconds, String model, int maxOutputTokens) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        return new OpenAiChatProvider(mapper, "test-key", model, timeoutSeconds,
            maxOutputTokens, uri, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    private OpenAiChatProvider provider(HttpServer server, int timeoutSeconds, String model,
                                        int maxOutputTokens, String reasoningEffort) {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        return new OpenAiChatProvider(mapper, "test-key", model, timeoutSeconds,
            maxOutputTokens, reasoningEffort, uri,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
