ALTER TABLE jobs
  ADD COLUMN lease_expires_at DATETIME NULL,
  ADD INDEX idx_jobs_status_lease_expires_at (status, lease_expires_at);
