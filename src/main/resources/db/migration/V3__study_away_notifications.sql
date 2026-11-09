-- UTC DATETIME(6), UUID CHAR(36) with the same collation as V1.
-- Push tokens are kept only for delivery and are removed with their owner.
CREATE TABLE push_devices (
 installation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expo_push_token VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (installation_id),
 UNIQUE KEY uq_push_devices_install_owner (installation_id,user_id),
 UNIQUE KEY uq_push_devices_token_hash (token_hash),
 KEY idx_push_devices_user (user_id),
 CONSTRAINT fk_push_devices_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One durable departure/outbox per session and app installation.
-- A foreground event cancels the pending delivery; a new departure may rearm it.
CREATE TABLE study_app_presence (
 session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 installation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 last_event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_sequence BIGINT NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 away_at DATETIME(6) NULL,
 due_at DATETIME(6) NULL,
 delivery_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 claim_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 claimed_at DATETIME(6) NULL,
 expo_ticket_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY (session_id,installation_id),
 KEY idx_presence_device_owner (installation_id,user_id),
 KEY idx_presence_owner_session (user_id,session_id),
 KEY idx_presence_due (delivery_status,due_at),
 CONSTRAINT fk_presence_session_owner FOREIGN KEY (session_id,user_id)
  REFERENCES study_sessions(id,user_id) ON DELETE CASCADE,
 CONSTRAINT fk_presence_device_owner FOREIGN KEY (installation_id,user_id)
  REFERENCES push_devices(installation_id,user_id) ON DELETE CASCADE,
 CONSTRAINT chk_presence_sequence CHECK (event_sequence >= 0),
 CONSTRAINT chk_presence_state CHECK (state IN ('FOREGROUND','BACKGROUND')),
 CONSTRAINT chk_presence_delivery CHECK (delivery_status IN
  ('NONE','PENDING','SENDING','SENT','CANCELED','FAILED','DELIVERY_UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
