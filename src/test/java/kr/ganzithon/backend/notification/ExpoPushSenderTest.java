package kr.ganzithon.backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpoPushSenderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger httpStatus = new AtomicInteger(200);
    private final AtomicReference<String> httpBody = new AtomicReference<>("{\"data\":{\"status\":\"ok\",\"id\":\"ticket-1\"}}");
    private final AtomicReference<String> sentBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile long delayMs;
    private HttpServer server;
    private URI endpoint;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/--/api/v2/push/send", exchange -> {
            try {
                sentBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                if (delayMs > 0) Thread.sleep(delayMs);
                byte[] bytes = httpBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(httpStatus.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/--/api/v2/push/send");
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void sendsExpectedJsonToLoopbackAndParsesTicket() throws Exception {
        UUID sessionId=UUID.randomUUID();
        PushSender.Result result=sender("private-test-access-token", Duration.ofSeconds(1))
                .send("ExpoPushToken[TestToken123456]", sessionId);
        assertEquals("SENT",result.status());
        assertEquals("ticket-1",result.ticketId());
        JsonNode request=mapper.readTree(sentBody.get());
        assertEquals("ExpoPushToken[TestToken123456]", request.path("to").asText());
        assertEquals(sessionId.toString(),request.path("data").path("sessionId").asText());
        assertEquals("STUDY_STILL_RUNNING",request.path("data").path("type").asText());
        assertEquals("Bearer private-test-access-token",authorization.get());
        assertFalse(result.toString().contains("TestToken123456"));
        assertFalse(result.toString().contains("private-test-access-token"));
    }

    @Test void recognizesExplicitRejectionAndUnknownFailures() {
        httpStatus.set(401);
        assertEquals("FAILED",sender("",Duration.ofSeconds(1)).send("ExpoPushToken[TestToken123]",UUID.randomUUID()).status());
        httpStatus.set(500);
        assertEquals("DELIVERY_UNKNOWN",sender("",Duration.ofSeconds(1))
                .send("ExpoPushToken[TestToken123]",UUID.randomUUID()).status());
        httpStatus.set(200);
        httpBody.set("{not-json");
        assertEquals("DELIVERY_UNKNOWN",sender("",Duration.ofSeconds(1))
                .send("ExpoPushToken[TestToken123]",UUID.randomUUID()).status());
    }

    @Test void rejectsOversizedOrErrorTicketsWithoutClaimingDelivery() {
        httpBody.set("x".repeat(131_073));
        assertEquals("DELIVERY_UNKNOWN",sender("",Duration.ofSeconds(1))
                .send("ExpoPushToken[TestToken123]",UUID.randomUUID()).status());
        httpBody.set("{\"data\":{\"status\":\"error\",\"message\":\"DeviceNotRegistered\"}}");
        assertEquals("FAILED",sender("",Duration.ofSeconds(1))
                .send("ExpoPushToken[TestToken123]",UUID.randomUUID()).status());
    }

    @Test void timeoutIsUnknownBecauseTheProviderMayHaveAcceptedTheMessage() {
        delayMs=400;
        assertEquals("DELIVERY_UNKNOWN",sender("",Duration.ofMillis(50))
                .send("ExpoPushToken[TestToken123]",UUID.randomUUID()).status());
    }

    private ExpoPushSender sender(String token,Duration timeout) {
        HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new ExpoPushSender(mapper,token,endpoint,client,timeout);
    }
}
