package com.example.jobs.api;

import com.example.jobs.common.Job;
import com.example.jobs.common.JobRepository;
import com.example.jobs.common.JobStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
// Keeping business decisions here prevents controllers from knowing persistence details.
public class JobService {
  private final JobRepository jobRepository;
  private final ObjectMapper objectMapper;

  // Dependencies are injected so serialization and persistence remain replaceable and testable.
  public JobService(JobRepository jobRepository, ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.objectMapper = objectMapper;
  }

  // The early lookup handles normal duplicates, while the database catch handles concurrent requests.
  public Job create(JobDtos.CreateJobRequest request) {
    Job existingJob = jobRepository.byKey(request.idempotencyKey()).orElse(null);
    if (existingJob != null) {
      return existingJob;
    }
    String payload;
    try {
      payload = objectMapper.writeValueAsString(request.payload());
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Payload must be valid JSON", exception);
    }
    try {
      return jobRepository.create(
          request.jobType(),
          payload,
          request.runAt() == null ? LocalDateTime.now(ZoneOffset.UTC) : request.runAt(),
          request.idempotencyKey(),
          request.maxAttempts() == null ? 3 : request.maxAttempts());
    } catch (DuplicateKeyException exception) {
      return jobRepository.byKey(request.idempotencyKey()).orElseThrow();
    }
  }

  // A missing job becomes an explicit not-found error at the service boundary.
  public Job get(long id) {
    return jobRepository
        .find(id)
        .orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
  }

  // Exposing the lookup lets the controller choose the correct HTTP status for an existing key.
  public java.util.Optional<Job> findByIdempotencyKey(String key) {
    return jobRepository.byKey(key);
  }

  // Delegating filtering and pagination preserves one SQL source of truth for job listings.
  public List<Job> list(JobStatus status, int limit, int offset) {
    return jobRepository.list(status, limit, offset);
  }

  public Job retry(long id) {
    Job job = get(id);
    if (job.status() != JobStatus.DEAD) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Job is not DEAD");
    }
    jobRepository.retry(id);
    return get(id);
  }
}
