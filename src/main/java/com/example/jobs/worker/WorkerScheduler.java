package com.example.jobs.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
// Conditional scheduling keeps API-only containers from running worker loops.
public class WorkerScheduler {
  private final JobWorker jobWorker;
  private final com.example.jobs.common.JobRepository jobRepository;
  private final Counter recoveredLeaseCounter;

  // Injecting the worker keeps scheduling orchestration separate from job execution.
  public WorkerScheduler(JobWorker jobWorker, com.example.jobs.common.JobRepository jobRepository,
      MeterRegistry meterRegistry) {
    this.jobWorker = jobWorker;
    this.jobRepository = jobRepository;
    recoveredLeaseCounter = Counter.builder("jobs_lease_recovered_total")
        .description("Jobs recovered after their worker lease expired")
        .register(meterRegistry);
  }

  // Fixed delay gives the worker a small pause only after its previous polling cycle finishes.
  @Scheduled(fixedDelayString = "${worker.poll-interval-ms:1000}")
  public void poll() {
    jobWorker.pollOnce();
  }

  @Scheduled(fixedDelayString = "${worker.recovery-interval-ms:10000}")
  public void recoverLeases() {
    recoveredLeaseCounter.increment(jobRepository.recoverExpiredLeases());
  }
}
