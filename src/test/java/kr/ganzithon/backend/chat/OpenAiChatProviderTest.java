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
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        return new OpenAiChatProvider(mapper, "test-key", "gpt-6-luna", timeoutSeconds,
            1024, uri, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    private void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
