package com.example.jobs.demo;

import com.example.jobs.common.Job;

public record DemoRetryResponse(long id, String status, int attempts, int maxAttempts) {
  public static DemoRetryResponse from(Job job) {
    return new DemoRetryResponse(job.id(), job.status().name(), job.attempts(), job.maxAttempts());
  }
}
