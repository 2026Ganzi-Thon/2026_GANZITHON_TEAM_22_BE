package kr.ganzithon.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Fresh, dedicated disposable databases only; never cleans or baselines an existing database. */
@EnabledIfEnvironmentVariable(named = "GANZI_MIGRATION_DB_URL", matches = ".+")
class StagingDatabaseMigrationTest {
    @Test
    void initialMigrationAndRepeatedStartupPreserveCommittedData() throws Exception {
        String url = guardedUrl("GANZI_MIGRATION_DB_URL", "migration");
        Flyway flyway = flyway(url);
        assertEquals(4, flyway.migrate().migrationsExecuted);
        String id = UUID.randomUUID().toString();
        try (Connection connection = connect(url)) {
            try (var insert = connection.prepareStatement("INSERT INTO users(id,created_at) VALUES(?,UTC_TIMESTAMP(6))")) {
                insert.setString(1, id);
                assertEquals(1, insert.executeUpdate());
            }
            assertEquals(0, flyway(url).migrate().migrationsExecuted);
            try (var select = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE id=?")) {
                select.setString(1, id);
                try (var rows = select.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                assertTrue(rows.next());
                assertEquals(16, rows.getInt(1)); // Fifteen domain tables plus Flyway history.
            }
            assertTrue(flyway.validateWithResult().validationSuccessful);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name='chat_memory_quarantine'")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GANZI_MIGRATION_UPGRADE_DB_URL", matches = ".+")
    void existingV1DataSurvivesTheMemoryAndNotificationUpgrade() throws Exception {
        String url = guardedUrl("GANZI_MIGRATION_UPGRADE_DB_URL", "upgrade");
        Flyway original = Flyway.configure().dataSource(url, required("GANZI_TEST_DB_USERNAME"),
                required("GANZI_TEST_DB_PASSWORD")).locations("classpath:db/migration")
                .target("1").baselineOnMigrate(false).cleanDisabled(true).load();
        assertEquals(1, original.migrate().migrationsExecuted);
        String id = UUID.randomUUID().toString();
        try (Connection connection = connect(url)) {
            try (var statement = connection.prepareStatement("INSERT INTO users(id,created_at) VALUES(?,UTC_TIMESTAMP(6))")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
        assertEquals(3, flyway(url).migrate().migrationsExecuted);
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE id=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name IN ('chat_memory','chat_memory_terms','push_devices','study_app_presence')")) {
                assertTrue(rows.next());
                assertEquals(4, rows.getInt(1));
            }
            assertTrue(flyway(url).validateWithResult().validationSuccessful);
        }
    }

    @Test
    void untrackedExistingDatabaseIsNotSilentlyAdopted() throws Exception {
        String url = guardedUrl("GANZI_MIGRATION_DIRTY_DB_URL", "dirty");
        try (Connection connection = connect(url); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE unrelated_existing_data(id INT PRIMARY KEY)");
            statement.execute("INSERT INTO unrelated_existing_data(id) VALUES(1)");
            assertThrows(FlywayException.class, () -> flyway(url).migrate());
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM unrelated_existing_data")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, required("GANZI_TEST_DB_USERNAME"),
                required("GANZI_TEST_DB_PASSWORD")).locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true).load();
    }

    private Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, required("GANZI_TEST_DB_USERNAME"),
                required("GANZI_TEST_DB_PASSWORD"));
    }

    private String guardedUrl(String variable, String suffix) {
        String url = required(variable);
        if (!url.startsWith("jdbc:mysql://")) throw new IllegalStateException("Local MySQL test URL required");
        URI uri = URI.create(url.substring("jdbc:".length()));
        if (!java.util.Set.of("127.0.0.1", "localhost").contains(uri.getHost()) || uri.getUserInfo() != null
                || uri.getPath() == null || !uri.getPath().matches("/ganzi_test_deploy_[a-zA-Z0-9_]+_" + suffix)) {
            throw new IllegalStateException("A dedicated, disposable ganzi_test_deploy_*_" + suffix + " DB is required");
        }
        return url;
    }

    private String required(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isBlank()) throw new IllegalStateException(variable + " is required");
        return value;
    }
}
