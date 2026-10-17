package com.example.jobs.worker;

import com.example.jobs.common.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
// Keeping polling separate from scheduling makes the worker easy to run deterministically in tests.
public class JobWorker {
  private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
  private final JobRepository jobRepository;
  private final JobHandlerRegistry registry;
  private final ObjectMapper objectMapper;
  private final String workerId;
  private final int batchSize;

  // Configuration is read once so every loop uses the same worker identity and batch policy.
  public JobWorker(
      JobRepository jobRepository,
      JobHandlerRegistry jobHandlerRegistry,
      ObjectMapper objectMapper,
      Environment environment) {
    this.jobRepository = jobRepository;
    registry = jobHandlerRegistry;
    this.objectMapper = objectMapper;
    workerId = environment.getProperty("worker.id", "worker-1");
    batchSize = environment.getProperty("worker.batch-size", Integer.class, 5);
  }

  // Continuing until an empty claim drains available work without adding queue latency between batches.
  public void pollOnce() {
    while (true) {
      List<Job> jobs = jobRepository.claimBatch(workerId, batchSize);
      if (jobs.isEmpty()) {
        return;
      }
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> tasks = new ArrayList<>();
        for (Job job : jobs) {
          tasks.add(executor.submit(() -> execute(job)));
        }
        tasks.forEach(
            task -> {
              try {
                task.get();
              } catch (Exception exception) {
                log.warn("Worker task failed", exception);
              }
            });
      }
    }
  }

  // Isolating execution lets one bad job fail independently without stopping the worker loop.
  private void execute(Job job) {
    try {
      var handler =
          registry
              .find(job.jobType())
              .orElseThrow(() -> new NonRetryableJobException("Unknown job type: " + job.jobType()));
      handler.execute(new JobContext(job.id(), job.attempts(), objectMapper.readTree(job.payload())));
      jobRepository.success(job.id(), workerId);
    } catch (NonRetryableJobException exception) {
      log.warn("Job {} is not retryable", job.id(), exception);
      jobRepository.recordFailure(job, exception.getMessage(), false, workerId);
    } catch (Exception exception) {
      log.warn("Job {} failed", job.id(), exception);
      jobRepository.recordFailure(job, exception.getMessage(), true, workerId);
    }
  }
}
