package kr.ganzithon.backend.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.ganzithon.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicChatProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendsOneStructuredRequestAndKeepsFourOrderedLines() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            try {
                assertEquals("POST", exchange.getRequestMethod());
                assertEquals("test-key", exchange.getRequestHeaders().getFirst("x-api-key"));
                request.set(mapper.readTree(exchange.getRequestBody()));
                String output = """
                    {"utterances":[{"characterKey":"JOY","content":"같이 시작해볼까?"},
                    {"characterKey":"ANGRY","content":"작은 것 하나부터 해보자."},
                    {"characterKey":"SAD","content":"천천히 해도 괜찮아."},
                    {"characterKey":"JOY","content":"여기서 함께할게."}]}
                    """;
                respond(exchange, 200, mapper.writeValueAsString(java.util.Map.of(
                    "stop_reason", "end_turn", "content", List.of(java.util.Map.of(
                        "type", "text", "text", output)))));
            } catch (Exception ex) {
                exchange.close();
            }
        });
        server.start();
        try {
            ChatProvider.Reply reply = provider(server, 2).generate(
                "공부를 시작하고 싶어", null, null, List.of("JOY", "ANGRY", "SAD"));
            assertEquals(1, calls.get());
            assertEquals(List.of("JOY", "ANGRY", "SAD", "JOY"),
                reply.utterances().stream().map(ChatProvider.Utterance::characterKey).toList());
            JsonNode payload = request.get();
            assertEquals("claude-haiku-5-5", payload.path("model").asText());
            assertEquals(256, payload.path("max_tokens").asInt());
            assertEquals("json_schema", payload.path("output_config").path("format").path("type").asText());
            assertEquals("공부를 시작하고 싶어", mapper.readTree(
                payload.path("messages").get(0).path("content").asText())
                .path("currentUserContent").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsIncompleteAndInvalidResponsesWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/v1/messages", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, """
                {"stop_reason":"max_tokens","content":[{"type":"text",
                "text":"{\\"utterances\\":[{\\"characterKey\\":\\"JOY\\",\\"content\\":\\"partial\\"}]}"}]}
                """);
        });
        server.start();
        try {
            ApiException ex = assertThrows(ApiException.class, () ->
                provider(server, 2).generate("시작할게", null, null, List.of("JOY")));
            assertEquals(502, ex.status());
            assertEquals("CHAT_UPSTREAM_INVALID", ex.code());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsTimeUntilFullBodyArrives() throws Exception {
        HttpServer server = server();
        server.createContext("/v1/messages", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                Thread.sleep(1800);
                exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // A canceled client may close the local connection before this fake response ends.
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            long started = System.nanoTime();
            ApiException ex = assertThrows(ApiException.class, () ->
                provider(server, 1).generate("시작할게", null, null, List.of("JOY")));
            assertEquals(504, ex.status());
            assertEquals("CHAT_UPSTREAM_TIMEOUT", ex.code());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1700);
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    }

    private AnthropicChatProvider provider(HttpServer server, int timeoutSeconds) {
        return new AnthropicChatProvider(mapper, "test-key", "claude-haiku-5-5",
            timeoutSeconds, 256, java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages"),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
        throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
