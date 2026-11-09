package kr.ganzithon.backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ExpoPushSender implements PushSender {
    private static final URI ENDPOINT = URI.create("https://exp.host/--/api/v2/push/send");
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String accessToken;
    private final URI endpoint;
    private final Duration timeout;

    @Autowired
    public ExpoPushSender(ObjectMapper mapper, @Value("${ganzi.push.expo-access-token:}") String accessToken) {
        this(mapper, accessToken, ENDPOINT, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), Duration.ofSeconds(5));
    }

    ExpoPushSender(ObjectMapper mapper, String accessToken, URI endpoint, HttpClient client) {
        this(mapper, accessToken, endpoint, client, Duration.ofSeconds(5));
    }

    ExpoPushSender(ObjectMapper mapper, String accessToken, URI endpoint, HttpClient client, Duration timeout) {
        this.mapper = mapper;
        this.accessToken = accessToken;
        this.endpoint = endpoint;
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public Result send(String token, UUID sessionId) {
        try {
            byte[] data = mapper.writeValueAsBytes(Map.of(
                    "to", token,
                    "title", "공부가 계속 진행 중이에요",
                    "body", "공부를 마쳤다면 앱에서 끝내기를 눌러주세요.",
                    "data", Map.of("type", "STUDY_STILL_RUNNING", "sessionId", sessionId.toString())));
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(data));
            if (!accessToken.isBlank()) builder.header("Authorization", "Bearer " + accessToken);
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403) {
                return Result.failed();
            }
            if (response.statusCode() != 200) return Result.unknown();
            if (response.body().length() > 131_072) return Result.unknown();
            JsonNode root = mapper.readTree(response.body());
            JsonNode ticket = root.path("data");
            if (ticket.isArray()) ticket = ticket.path(0);
            if ("ok".equals(ticket.path("status").asText()) && ticket.path("id").isTextual()) {
                return Result.sent(ticket.path("id").asText());
            }
            return "error".equals(ticket.path("status").asText()) ? Result.failed() : Result.unknown();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.unknown();
        } catch (Exception ex) {
            // Timeout/network failure may have happened after Expo accepted the request.
            return Result.unknown();
        }
    }
}
