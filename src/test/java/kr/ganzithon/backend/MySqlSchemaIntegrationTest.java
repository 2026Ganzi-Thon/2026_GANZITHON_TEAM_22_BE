package kr.ganzithon.backend;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class MySqlSchemaIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void mysqlSchemaCanRunAgainWithoutLosingDataAndEnforcesForeignKeysAndChecks() {
        String user = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.parse("2026-11-06T00:00:00");
        jdbc.update("INSERT INTO users(id,created_at) VALUES (?,?)", user, now);
        try {
            jdbc.update("INSERT INTO tasks(id,user_id,title,status,created_at,updated_at) "
                    + "VALUES (?,?,?,'TODO',?,?)", task, user, "유지할 할 일", now, now);

            ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                    new ClassPathResource("schema-mysql.sql"));
            schema.execute(dataSource);
            schema.execute(dataSource);

            assertEquals("유지할 할 일", jdbc.queryForObject(
                    "SELECT title FROM tasks WHERE id = ?", String.class, task));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO subtasks(id,task_id,title,status,position,created_at,updated_at) "
                            + "VALUES (?,?,?,'TODO',1,?,?)",
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), "잘못된 부모", now, now));
            DataAccessException invalidStatus = assertThrows(DataAccessException.class, () -> jdbc.update(
                    "UPDATE tasks SET status = 'INVALID' WHERE id = ?", task));
            SQLException constraintError = (SQLException) invalidStatus.getMostSpecificCause();
            assertEquals(3819, constraintError.getErrorCode()); // ER_CHECK_CONSTRAINT_VIOLATED

            String subtask = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO subtasks(id,task_id,title,status,position,created_at,updated_at) "
                    + "VALUES (?,?,?,'TODO',1,?,?)", subtask, task, "자식", now, now);
            jdbc.update("DELETE FROM tasks WHERE id = ?", task);
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subtasks WHERE id = ?", Integer.class, subtask));
        } finally {
            jdbc.update("DELETE FROM tasks WHERE id = ?", task);
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }
}
