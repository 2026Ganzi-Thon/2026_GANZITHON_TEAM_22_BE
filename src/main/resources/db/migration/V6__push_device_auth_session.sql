-- Prior installations have no trusted session association. Keep their rows unbound.
ALTER TABLE push_devices
 ADD COLUMN auth_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD UNIQUE KEY uq_push_devices_auth_session (auth_session_id),
 ADD CONSTRAINT fk_push_devices_auth_session FOREIGN KEY (auth_session_id)
  REFERENCES auth_sessions(id) ON DELETE SET NULL;
