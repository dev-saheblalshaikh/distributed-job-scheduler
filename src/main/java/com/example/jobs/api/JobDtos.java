package com.example.jobs.api;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;
public final class JobDtos { private JobDtos() {}
 public record CreateJobRequest(@NotBlank String jobType, JsonNode payload, LocalDateTime runAt, String idempotencyKey) {}
 public record JobResponse(Long id,String jobType,JsonNode payload,String status,int attempts,int maxAttempts,LocalDateTime runAt,String lastError, String idempotencyKey) {}
}
