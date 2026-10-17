package com.example.jobs.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

public final class JobDtos {
  private JobDtos() {}

  public record CreateJobRequest(
      @NotBlank String jobType, JsonNode payload, LocalDateTime runAt, String idempotencyKey,
      @Min(1) @Max(10) Integer maxAttempts) {}

  public record JobResponse(
      Long id,
      String jobType,
      JsonNode payload,
      String status,
      int attempts,
      int maxAttempts,
      LocalDateTime runAt,
      String lastError,
      String idempotencyKey) {}
}
