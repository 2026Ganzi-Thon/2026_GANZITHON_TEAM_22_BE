package kr.ganzithon.backend;

import java.net.URI;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Refuse to initialize schemas against anything except an explicitly selected local test DB. */
public final class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String url = required("GANZI_TEST_DB_URL");
        required("GANZI_TEST_DB_USERNAME");
        if (System.getenv("GANZI_TEST_DB_PASSWORD") == null) {
            throw new IllegalStateException("GANZI_TEST_DB_PASSWORD must be explicitly set for tests");
        }
        if (!url.equals(context.getEnvironment().getProperty("spring.datasource.url"))) {
            throw new IllegalStateException("The test datasource must use GANZI_TEST_DB_URL");
        }
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("Tests require a local MySQL JDBC URL");
        }
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid GANZI_TEST_DB_URL", ex);
        }
        String database = uri.getPath();
        if (!Set.of("127.0.0.1", "localhost").contains(uri.getHost())
                || uri.getUserInfo() != null || database == null
                || !database.matches("/ganzi_test_[a-zA-Z0-9_]+")) {
            throw new IllegalStateException("Tests require a local ganzi_test_* database without URL credentials");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be explicitly set for tests");
        }
        return value;
    }
}
