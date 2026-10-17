package com.example.jobs.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
// Conditional scheduling keeps API-only containers from running worker loops.
public class WorkerScheduler {
  private final JobWorker jobWorker;

  // Injecting the worker keeps scheduling orchestration separate from job execution.
  public WorkerScheduler(JobWorker jobWorker) {
    this.jobWorker = jobWorker;
  }

  // Fixed delay gives the worker a small pause only after its previous polling cycle finishes.
  @Scheduled(fixedDelayString = "${worker.poll-interval-ms:1000}")
  public void poll() {
    jobWorker.pollOnce();
  }
}
