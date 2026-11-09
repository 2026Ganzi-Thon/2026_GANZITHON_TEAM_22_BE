-- MySQL 8.0.16+ / InnoDB. Java must bind and read every DATETIME(6) value as UTC.
-- All UUID columns use the same binary ASCII collation so composite foreign keys match.
CREATE TABLE users (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE auth_sessions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 secret_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_auth_sessions_secret_hash (secret_hash),
 KEY idx_auth_sessions_user_id (user_id),
 CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE email_accounts (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 normalized_email VARCHAR(254) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 password_hash VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 email_verified BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (user_id),
 UNIQUE KEY uq_email_accounts_normalized_email (normalized_email),
 CONSTRAINT fk_email_accounts_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tasks (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(240) NOT NULL,
 description VARCHAR(2000) NULL,
 due_date DATE NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 completed_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_tasks_id_user (id,user_id),
 KEY idx_tasks_user_status_due_id (user_id,status,due_date,id),
 CONSTRAINT fk_tasks_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT chk_tasks_status CHECK (status IN ('TODO','DONE')),
 CONSTRAINT chk_tasks_completed CHECK (
  (status='TODO' AND completed_at IS NULL) OR
  (status='DONE' AND completed_at IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE subtasks (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 title VARCHAR(240) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 position INT NOT NULL,
 completed_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_subtasks_task_position (task_id,position),
 KEY idx_subtasks_task_position_id (task_id,position,id),
 CONSTRAINT fk_subtasks_task FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
 CONSTRAINT chk_subtasks_status CHECK (status IN ('TODO','DONE')),
 CONSTRAINT chk_subtasks_position CHECK (position >= 1),
 CONSTRAINT chk_subtasks_completed CHECK (
  (status='TODO' AND completed_at IS NULL) OR
  (status='DONE' AND completed_at IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE study_sessions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 start_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 start_request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version INT NOT NULL,
 accumulated_ms BIGINT NOT NULL,
 running_started_at DATETIME(6) NULL,
 started_at DATETIME(6) NOT NULL,
 ended_at DATETIME(6) NULL,
 end_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 active_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
  GENERATED ALWAYS AS (CASE WHEN status <> 'ENDED' THEN user_id ELSE NULL END) STORED,
 PRIMARY KEY (id),
 UNIQUE KEY uq_study_sessions_id_user (id,user_id),
 UNIQUE KEY uq_study_sessions_user_request (user_id,start_request_id),
 UNIQUE KEY uq_study_sessions_active_user (active_user_id),
 KEY idx_study_sessions_task_owner (task_id,user_id),
 KEY idx_study_sessions_user_started (user_id,started_at,id),
 KEY idx_study_sessions_user_status_ended_task (user_id,status,ended_at,task_id),
 CONSTRAINT fk_study_sessions_user FOREIGN KEY (user_id) REFERENCES users(id),
 CONSTRAINT fk_study_sessions_task_owner FOREIGN KEY (task_id,user_id) REFERENCES tasks(id,user_id),
 CONSTRAINT chk_study_sessions_status CHECK (status IN ('STUDYING','PAUSED','ENDED')),
 CONSTRAINT chk_study_sessions_version CHECK (version >= 0),
 CONSTRAINT chk_study_sessions_accumulated CHECK (accumulated_ms >= 0),
 CONSTRAINT chk_study_sessions_end_reason CHECK (end_reason IN ('USER_STOPPED','USER_FINISHED')),
 CONSTRAINT chk_study_sessions_state CHECK (
  (status='STUDYING' AND running_started_at IS NOT NULL AND ended_at IS NULL AND end_reason IS NULL) OR
  (status='PAUSED' AND running_started_at IS NULL AND ended_at IS NULL AND end_reason IS NULL) OR
  (status='ENDED' AND running_started_at IS NULL AND ended_at IS NOT NULL AND end_reason IS NOT NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- A reminder records an OS scheduling intent. The mobile client owns delivery.
CREATE TABLE start_reminders (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 remind_at DATETIME(6) NOT NULL,
 time_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 canceled_reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 -- VIRTUAL permits the composite owner FK to cascade deletion from study_sessions.
 active_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin
  GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN user_id ELSE NULL END) VIRTUAL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_start_reminders_source_session (source_session_id),
 UNIQUE KEY uq_start_reminders_active_user (active_user_id),
 KEY idx_start_reminders_source_owner (source_session_id,user_id),
 KEY idx_start_reminders_user_status (user_id,status),
 CONSTRAINT fk_start_reminders_session_owner FOREIGN KEY (source_session_id,user_id)
  REFERENCES study_sessions(id,user_id) ON DELETE CASCADE,
 CONSTRAINT chk_start_reminders_status CHECK (status IN ('ACTIVE','CANCELED','EXPIRED')),
 CONSTRAINT chk_start_reminders_reason CHECK (canceled_reason IN ('USER_CANCELED','NEW_STUDY')),
 CONSTRAINT chk_start_reminders_state CHECK (
  (status='CANCELED' AND canceled_reason IS NOT NULL) OR
  (status IN ('ACTIVE','EXPIRED') AND canceled_reason IS NULL)
 )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_spend_periods (
 scope VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 period_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 period_start DATE NOT NULL,
 charged_nano_usd BIGINT NOT NULL,
 reserved_nano_usd BIGINT NOT NULL,
 PRIMARY KEY (scope,period_kind,period_start),
 CONSTRAINT chk_chat_spend_periods_kind CHECK (period_kind IN ('DAY','MONTH')),
 CONSTRAINT chk_chat_spend_periods_charged CHECK (charged_nano_usd >= 0),
 CONSTRAINT chk_chat_spend_periods_reserved CHECK (reserved_nano_usd >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_usage_events (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 scope VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 model VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 day_start DATE NOT NULL,
 month_start DATE NOT NULL,
 reserved_nano_usd BIGINT NOT NULL,
 charged_nano_usd BIGINT NULL,
 input_nano_rate BIGINT NOT NULL,
 output_nano_rate BIGINT NOT NULL,
 max_input_tokens BIGINT NOT NULL,
 max_output_tokens INT NOT NULL,
 input_tokens BIGINT NULL,
 cached_input_tokens BIGINT NULL,
 output_tokens BIGINT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_chat_usage_scope_user_request (scope,user_id,client_request_id),
 KEY idx_chat_usage_scope_user_day (scope,user_id,day_start),
 KEY idx_chat_usage_scope_user_month (scope,user_id,month_start),
 CONSTRAINT fk_chat_usage_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
 CONSTRAINT chk_chat_usage_reserved CHECK (reserved_nano_usd >= 0),
 CONSTRAINT chk_chat_usage_charged CHECK (charged_nano_usd IS NULL OR charged_nano_usd >= 0),
 CONSTRAINT chk_chat_usage_input_rate CHECK (input_nano_rate >= 0),
 CONSTRAINT chk_chat_usage_output_rate CHECK (output_nano_rate >= 0),
 CONSTRAINT chk_chat_usage_max_input CHECK (max_input_tokens >= 0),
 CONSTRAINT chk_chat_usage_max_output CHECK (max_output_tokens >= 0),
 CONSTRAINT chk_chat_usage_input_tokens CHECK (input_tokens IS NULL OR input_tokens >= 0),
 CONSTRAINT chk_chat_usage_cached_tokens CHECK (cached_input_tokens IS NULL OR cached_input_tokens >= 0),
 CONSTRAINT chk_chat_usage_output_tokens CHECK (output_tokens IS NULL OR output_tokens >= 0),
 CONSTRAINT chk_chat_usage_status CHECK (status IN ('RESERVED','SETTLED','UNKNOWN','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE chat_billing_state (
 scope VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 blocked_epoch BIGINT NULL,
 reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (scope),
 CONSTRAINT chk_chat_billing_epoch CHECK (blocked_epoch IS NULL OR blocked_epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
