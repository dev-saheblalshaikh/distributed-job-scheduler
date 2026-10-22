package com.example.jobs.demo;

import com.example.jobs.common.JobRepository;
import com.example.jobs.common.JobStatus;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DemoRepository {
  private final JdbcTemplate jdbcTemplate;
  private final JobRepository jobRepository;

  public DemoRepository(JdbcTemplate jdbcTemplate, JobRepository jobRepository) {
    this.jdbcTemplate = jdbcTemplate;
    this.jobRepository = jobRepository;
  }

  public int activeCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from jobs where origin='demo' and status in ('PENDING','RUNNING')", Integer.class);
  }

  public List<Map<String, Object>> stateRows() {
    return jdbcTemplate.queryForList(
        "select id,job_type,status,attempts,max_attempts,last_error,locked_by,created_at,updated_at from jobs where origin='demo' order by id desc limit 30");
  }

  public Map<String, Object> counts() {
    return jdbcTemplate.queryForList(
        "select status,count(*) as count from jobs where origin='demo' group by status")
        .stream()
        .collect(java.util.stream.Collectors.toMap(row -> (String) row.get("status"), row -> row.get("count")));
  }

  public Long oldestPendingAgeSeconds() {
    return jdbcTemplate.queryForObject(
        "select coalesce(timestampdiff(second,min(created_at),UTC_TIMESTAMP()),0) from jobs where origin='demo' and status='PENDING'",
        Long.class);
  }

  public boolean retryDemo(long id) {
    return jdbcTemplate.update(
        "update jobs set status='PENDING',attempts=0,run_at=UTC_TIMESTAMP(),locked_by=NULL,locked_at=NULL,lease_expires_at=NULL,updated_at=UTC_TIMESTAMP() where id=? and origin='demo' and status='DEAD'",
        id) == 1;
  }

  public int cleanup() {
    return jdbcTemplate.update(
        "delete from jobs where origin='demo' and ((status in ('SUCCESS','DEAD') and updated_at<UTC_TIMESTAMP()-interval 1 hour) or created_at<UTC_TIMESTAMP()-interval 3 hour)");
  }
}
