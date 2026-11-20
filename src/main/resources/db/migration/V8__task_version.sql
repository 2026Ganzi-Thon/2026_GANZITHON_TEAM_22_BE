ALTER TABLE tasks
 ADD COLUMN version INT NOT NULL DEFAULT 0,
 ADD CONSTRAINT chk_tasks_version CHECK (version >= 0);
