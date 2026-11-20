package kr.ganzithon.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Fresh, dedicated disposable databases only; never cleans or baselines an existing database. */
@EnabledIfEnvironmentVariable(named = "GANZI_MIGRATION_DB_URL", matches = ".+")
class StagingDatabaseMigrationTest {
    @Test
    void initialMigrationAndRepeatedStartupPreserveCommittedData() throws Exception {
        String url = guardedUrl("GANZI_MIGRATION_DB_URL", "migration");
        Flyway flyway = flyway(url);
        assertEquals(8, flyway.migrate().migrationsExecuted);
        assertEquals("8", flyway.info().current().getVersion().getVersion());
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
                assertEquals(17, rows.getInt(1)); // Sixteen domain tables plus Flyway history.
            }
            assertTrue(flyway.validateWithResult().validationSuccessful);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name IN ('chat_memory_quarantine','task_create_requests')")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GANZI_MIGRATION_UPGRADE_DB_URL", matches = ".+")
    void existingV6DataAndPushRegistrationSurviveTheUpgrade() throws Exception {
        String url = guardedUrl("GANZI_MIGRATION_UPGRADE_DB_URL", "upgrade");
        Flyway original = configuration(url).target("1").load();
        assertEquals(1, original.migrate().migrationsExecuted);
        String id = UUID.randomUUID().toString();
        String installation = UUID.randomUUID().toString();
        String session = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        String pushToken = "ExpoPushToken[synthetic-legacy-device]";
        try (Connection connection = connect(url)) {
            try (var statement = connection.prepareStatement("INSERT INTO users(id,created_at) VALUES(?,UTC_TIMESTAMP(6))")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            assertEquals(4, configuration(url).target("5").load().migrate().migrationsExecuted);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO push_devices
                        (installation_id,user_id,expo_push_token,token_hash,created_at,updated_at)
                    VALUES (?,?,?,SHA2(?,256),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """)) {
                insert.setString(1, installation);
                insert.setString(2, id);
                insert.setString(3, pushToken);
                insert.setString(4, pushToken);
                assertEquals(1, insert.executeUpdate());
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO study_sessions (id,user_id,start_request_id,start_request_hash,status,version,
                        accumulated_ms,running_started_at,started_at,created_at,updated_at)
                    VALUES (?,?,?,REPEAT('0',64),'STUDYING',0,0,
                        UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """)) {
                insert.setString(1, session);
                insert.setString(2, id);
                insert.setString(3, UUID.randomUUID().toString());
                assertEquals(1, insert.executeUpdate());
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO study_app_presence (session_id,installation_id,user_id,last_event_id,
                        event_sequence,state,away_at,due_at,delivery_status,updated_at)
                    VALUES (?,?,?,?,1,'BACKGROUND',UTC_TIMESTAMP(6),
                        UTC_TIMESTAMP(6) + INTERVAL 5 MINUTE,'PENDING',UTC_TIMESTAMP(6))
                    """)) {
                insert.setString(1, session);
                insert.setString(2, installation);
                insert.setString(3, id);
                insert.setString(4, UUID.randomUUID().toString());
                assertEquals(1, insert.executeUpdate());
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO tasks(id,user_id,title,status,created_at,updated_at)
                    VALUES (?,?,'기존 할 일','TODO',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """)) {
                insert.setString(1, task);
                insert.setString(2, id);
                assertEquals(1, insert.executeUpdate());
            }
            assertEquals(1, configuration(url).target("6").load().migrate().migrationsExecuted);
            assertEquals(2, flyway(url).migrate().migrationsExecuted);
            try (var select = connection.prepareStatement("""
                    SELECT d.user_id,d.expo_push_token,d.auth_session_id,p.session_id,p.delivery_status
                    FROM push_devices d JOIN study_app_presence p USING (installation_id,user_id)
                    WHERE d.installation_id=?
                    """)) {
                select.setString(1, installation);
                try (var rows = select.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(id, rows.getString("user_id"));
                    assertEquals(pushToken, rows.getString("expo_push_token"));
                    assertNull(rows.getString("auth_session_id"));
                    assertEquals(session, rows.getString("session_id"));
                    assertEquals("PENDING", rows.getString("delivery_status"));
                    assertFalse(rows.next());
                }
            }
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM users WHERE id=?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                }
            }
            try (var statement = connection.prepareStatement("SELECT title,version FROM tasks WHERE id=?")) {
                statement.setString(1, task);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("기존 할 일", rows.getString("title"));
                    assertEquals(0, rows.getInt("version"));
                    assertFalse(rows.next());
                }
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                            + "AND table_name IN ('chat_memory','chat_memory_terms','push_devices','study_app_presence','task_create_requests')")) {
                assertTrue(rows.next());
                assertEquals(5, rows.getInt(1));
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
        return configuration(url).load();
    }

    private FluentConfiguration configuration(String url) {
        return Flyway.configure().dataSource(url, required("GANZI_TEST_DB_USERNAME"),
                required("GANZI_TEST_DB_PASSWORD")).locations("classpath:db/migration")
                .baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true);
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
