-- A NULL rate means the event was admitted by an earlier deployment; use its input rate.
ALTER TABLE chat_usage_events
 ADD COLUMN cached_nano_rate BIGINT NULL,
 ADD COLUMN cache_write_nano_rate BIGINT NULL,
 ADD COLUMN cache_write_input_tokens BIGINT NULL,
 ADD CONSTRAINT chk_chat_usage_cached_rate CHECK (cached_nano_rate IS NULL OR cached_nano_rate >= 0),
 ADD CONSTRAINT chk_chat_usage_cache_write_rate CHECK (cache_write_nano_rate IS NULL OR cache_write_nano_rate >= 0),
 ADD CONSTRAINT chk_chat_usage_cache_write_tokens CHECK (cache_write_input_tokens IS NULL OR cache_write_input_tokens >= 0);
