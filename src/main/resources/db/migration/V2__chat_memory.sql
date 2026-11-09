-- A completed exchange survives study-room deletion and is removed with its owner.
-- Raw UTF-8 JSON is archived losslessly after 60 days; archives have no expiry.
CREATE TABLE chat_memory (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sequence_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 study_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_bytes INT UNSIGNED NOT NULL,
 raw_payload MEDIUMTEXT NULL,
 compressed_payload MEDIUMBLOB NULL,
 created_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NOT NULL,
 archived_at DATETIME(6) NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_chat_memory_sequence (sequence_id),
 UNIQUE KEY uq_chat_memory_owner_request (user_id,client_request_id),
 UNIQUE KEY uq_chat_memory_owner_id (user_id,id),
 KEY idx_chat_memory_owner_finished (user_id,finished_at,sequence_id),
 KEY idx_chat_memory_owner_status_finished (user_id,status,finished_at,sequence_id),
 KEY idx_chat_memory_archive (archived_at,finished_at,sequence_id),
 CONSTRAINT fk_chat_memory_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 CONSTRAINT chk_chat_memory_status CHECK (status IN ('SUCCEEDED','FAILED')),
 CONSTRAINT chk_chat_memory_payload CHECK (
  (raw_payload IS NOT NULL AND compressed_payload IS NULL AND archived_at IS NULL)
  OR (raw_payload IS NULL AND compressed_payload IS NOT NULL AND archived_at IS NOT NULL)
 ),
 CONSTRAINT chk_chat_memory_size CHECK (payload_bytes > 0 AND payload_bytes <= 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Hashed lexical clues permit bounded lookup even after raw text has been compressed.
CREATE TABLE chat_memory_terms (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 term_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY (user_id,term_hash,memory_id),
 KEY idx_chat_memory_terms_memory (user_id,memory_id),
 CONSTRAINT fk_chat_memory_terms_memory FOREIGN KEY (user_id,memory_id)
  REFERENCES chat_memory(user_id,id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
