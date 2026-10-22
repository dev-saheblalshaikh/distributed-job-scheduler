package com.example.jobs.common;
import java.time.LocalDateTime;
public record Job(
    Long id,
    String jobType,
    String payload,
    JobStatus status,
    int attempts,
    int maxAttempts,
    LocalDateTime runAt,
    String lockedBy,
    LocalDateTime lockedAt,
    LocalDateTime leaseExpiresAt,
    String lastError,
    String idempotencyKey,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String origin) {}
