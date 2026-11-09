-- A separate table also upgrades existing local/demo databases where the
-- idempotent schema-mysql.sql cannot ALTER a previously created chat_memory.
-- Damaged payload bytes remain untouched; exact replay still returns 503.
CREATE TABLE chat_memory_quarantine (
 memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 detected_at DATETIME(6) NOT NULL,
 PRIMARY KEY (memory_id),
 CONSTRAINT fk_chat_memory_quarantine_memory FOREIGN KEY (user_id,memory_id)
  REFERENCES chat_memory(user_id,id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
