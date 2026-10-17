CREATE TABLE jobs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, job_type VARCHAR(100) NOT NULL, payload JSON NOT NULL,
 status ENUM('PENDING','RUNNING','SUCCESS','FAILED','DEAD') NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 3, run_at DATETIME NOT NULL,
 locked_by VARCHAR(100) NULL, locked_at DATETIME NULL, last_error TEXT NULL,
 idempotency_key VARCHAR(100) UNIQUE NULL, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
 INDEX idx_jobs_status_run_at (status, run_at)
);
