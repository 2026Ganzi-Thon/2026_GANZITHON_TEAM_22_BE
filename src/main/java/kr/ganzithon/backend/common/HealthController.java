package kr.ganzithon.backend.common;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final boolean checkDatabase;

    public HealthController(JdbcTemplate jdbc,
                            @Value("${ganzi.health.check-database:false}") boolean checkDatabase) {
        this.jdbc = jdbc;
        this.checkDatabase = checkDatabase;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        if (checkDatabase) {
            try {
                Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
                if (!Integer.valueOf(1).equals(result)) throw databaseNotReady();
            } catch (RuntimeException failure) {
                throw databaseNotReady();
            }
        }
        return Map.of("status", "UP");
    }

    private static ApiException databaseNotReady() {
        return new ApiException(503, "DATABASE_NOT_READY", "서비스를 준비하는 중입니다.");
    }
}
