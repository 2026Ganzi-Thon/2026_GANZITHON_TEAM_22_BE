-- A create request is retained when its result is deleted, preventing retry resurrection.
CREATE TABLE task_create_requests (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 resource_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 parent_task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY (user_id,request_id),
 CONSTRAINT fk_task_create_requests_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
 CONSTRAINT chk_task_create_requests_type CHECK (resource_type IN ('TASK','SUBTASK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
