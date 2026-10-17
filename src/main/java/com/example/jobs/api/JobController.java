package com.example.jobs.api;

import com.example.jobs.common.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/jobs")
@ConditionalOnProperty(name = "api.enabled", havingValue = "true", matchIfMissing = true)
// Conditional creation lets worker-only containers run without exposing API endpoints.
public class JobController {
  private final JobService service;
  private final ObjectMapper objectMapper;

  // Injecting ObjectMapper keeps response conversion aligned with application-wide Jackson settings.
  public JobController(JobService service, ObjectMapper objectMapper) {
    this.service = service;
    this.objectMapper = objectMapper;
  }

  // Checking before creation distinguishes a replayed request from a newly accepted job.
  @PostMapping
  public ResponseEntity<JobDtos.JobResponse> create(
      @Valid @RequestBody JobDtos.CreateJobRequest request) {
    var existing = service.findByIdempotencyKey(request.idempotencyKey()).isPresent();
    var job = service.create(request);
    return ResponseEntity.status(existing ? HttpStatus.OK : HttpStatus.CREATED)
        .body(toResponse(job, objectMapper));
  }

  // The service owns not-found behavior so every caller receives the same error semantics.
  @GetMapping("/{id}")
  public JobDtos.JobResponse get(@PathVariable long id) {
    return toResponse(service.get(id), objectMapper);
  }

  // Rejecting invalid limits early prevents accidental unbounded or nonsensical queries.
  @GetMapping
  public List<JobDtos.JobResponse> list(
      @RequestParam(required = false) JobStatus status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be at least 1");
    }
    return service.list(status, Math.min(limit, 100), Math.max(offset, 0)).stream()
        .map(job -> toResponse(job, objectMapper))
        .toList();
  }

  @PostMapping("/{id}/retry")
  public JobDtos.JobResponse retry(@PathVariable long id) {
    return toResponse(service.retry(id), objectMapper);
  }

  static JobDtos.JobResponse toResponse(Job job, ObjectMapper objectMapper) {
    try {
      return new JobDtos.JobResponse(
          job.id(),
          job.jobType(),
          objectMapper.readTree(job.payload()),
          job.status().name(),
          job.attempts(),
          job.maxAttempts(),
          job.runAt(),
          job.lastError(),
          job.idempotencyKey());
    } catch (Exception exception) {
      throw new IllegalStateException("Stored payload is invalid", exception);
    }
  }
}
