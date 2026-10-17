package com.example.jobs.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;
import com.example.jobs.worker.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Repository
// Centralizing SQL here keeps state transitions consistent across API and worker threads.
public class JobRepository {
  private static final Logger log = LoggerFactory.getLogger(JobRepository.class);
  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final RetryPolicy retryPolicy;

  // Keeping both collaborators injected makes the claim transaction use the same database access layer.
  public JobRepository(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, RetryPolicy retryPolicy) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.retryPolicy = retryPolicy;
  }

  // Mapping in one place prevents different callers from interpreting persisted job state differently.
  private Job map(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Job(
        resultSet.getLong("id"),
        resultSet.getString("job_type"),
        resultSet.getString("payload"),
        JobStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempts"),
        resultSet.getInt("max_attempts"),
        resultSet.getTimestamp("run_at").toLocalDateTime(),
        (String) resultSet.getObject("locked_by"),
        resultSet.getTimestamp("locked_at") == null
            ? null
            : resultSet.getTimestamp("locked_at").toLocalDateTime(),
        resultSet.getString("last_error"),
        resultSet.getString("idempotency_key"),
        resultSet.getTimestamp("created_at").toLocalDateTime(),
        resultSet.getTimestamp("updated_at").toLocalDateTime());
  }

  // Returning an Optional keeps a missing database row explicit for callers.
  public Optional<Job> find(long jobId) {
    return jdbcTemplate.query("select * from jobs where id=?", this::map, jobId).stream()
        .findFirst();
  }

  // A null key has no idempotency constraint, so it should not query for a fake key.
  public Optional<Job> byKey(String key) {
    return key == null
        ? Optional.empty()
        : jdbcTemplate.query("select * from jobs where idempotency_key=?", this::map, key).stream()
            .findFirst();
  }

  // UTC_TIMESTAMP() makes timestamps independent of the database server's local timezone.
  public Job create(String type, String payload, LocalDateTime runAt, String key, int maxAttempts) {
    GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  "insert into jobs(job_type,payload,run_at,max_attempts,idempotency_key,created_at,updated_at) values(?,?,?,?,?,UTC_TIMESTAMP(),UTC_TIMESTAMP())",
                  Statement.RETURN_GENERATED_KEYS);
          statement.setString(1, type);
          statement.setString(2, payload);
          statement.setObject(3, runAt);
          statement.setInt(4, maxAttempts);
          statement.setString(5, key);
          return statement;
        },
        keyHolder);
    return find(keyHolder.getKey().longValue()).orElseThrow();
  }

  // One transaction makes selection and state transition atomic, so concurrent workers cannot claim the same row.
  public List<Job> claimBatch(String workerId, int batchSize) {
    return transactionTemplate.execute(
        status -> {
          // SKIP LOCKED lets another worker move on instead of waiting for this worker's rows.
          List<Job> jobs =
              jdbcTemplate.query(
                  "select * from jobs where status='PENDING' and run_at<=UTC_TIMESTAMP() order by run_at,id limit ? for update skip locked",
                  this::map,
                  batchSize);
          if (jobs.isEmpty()) {
            return jobs;
          }
          List<Long> jobIds = jobs.stream().map(Job::id).toList();
          String placeholders = String.join(",", java.util.Collections.nCopies(jobIds.size(), "?"));
          Object[] parameters = new Object[jobIds.size() + 1];
          parameters[0] = workerId;
          for (int index = 0; index < jobIds.size(); index++) {
            parameters[index + 1] = jobIds.get(index);
          }
          jdbcTemplate.update(
              "update jobs set status='RUNNING',locked_by=?,locked_at=UTC_TIMESTAMP(),attempts=attempts+1,updated_at=UTC_TIMESTAMP() where id in ("
                  + placeholders
                  + ")",
              parameters);
          return jobs.stream()
              .map(
                  job ->
                      new Job(
                          job.id(),
                          job.jobType(),
                          job.payload(),
                          JobStatus.RUNNING,
                          job.attempts() + 1,
                          job.maxAttempts(),
                          job.runAt(),
                          workerId,
                          LocalDateTime.now(java.time.ZoneOffset.UTC),
                          job.lastError(),
                          job.idempotencyKey(),
                          job.createdAt(),
                          LocalDateTime.now(java.time.ZoneOffset.UTC)))
              .toList();
        });
  }

  // UTC_TIMESTAMP() keeps completion times comparable across containers in different host timezones.
  public void success(long id, String workerId) {
    int updated = jdbcTemplate.update(
        "update jobs set status='SUCCESS',updated_at=UTC_TIMESTAMP() where id=? and status='RUNNING' and locked_by=?", id, workerId);
    if (updated == 0) {
      log.warn("Worker {} could not complete job {} because ownership changed", workerId, id);
    }
  }

  public void recordFailure(Job job, String error, boolean retryable, String workerId) {
    boolean shouldRetry = retryable && job.attempts() < job.maxAttempts();
    long delaySeconds = retryPolicy.delayForAttempt(job.attempts()).toSeconds();
    String status = shouldRetry ? "PENDING" : "DEAD";
    int updated = jdbcTemplate.update(
        "update jobs set status=?,run_at=UTC_TIMESTAMP()+INTERVAL ? SECOND,last_error=?,locked_by=NULL,locked_at=NULL,updated_at=UTC_TIMESTAMP() where id=? and status='RUNNING' and locked_by=?",
        status, delaySeconds, error, job.id(), workerId);
    if (updated == 0) {
      log.warn("Worker {} could not record failure for job {} because ownership changed", workerId, job.id());
    }
  }

  public boolean retry(long id) {
    return jdbcTemplate.update(
        "update jobs set status='PENDING',attempts=0,run_at=UTC_TIMESTAMP(),locked_by=NULL,locked_at=NULL,updated_at=UTC_TIMESTAMP() where id=? and status='DEAD'",
        id) == 1;
  }

  // Applying pagination in SQL avoids loading the complete job table into memory.
  public List<Job> list(JobStatus status, int limit, int offset) {
    return jdbcTemplate.query(
        "select * from jobs where (? is null or status=?) order by id desc limit ? offset ?",
        this::map,
        status == null ? null : status.name(),
        status == null ? null : status.name(),
        limit,
        offset);
  }
}
