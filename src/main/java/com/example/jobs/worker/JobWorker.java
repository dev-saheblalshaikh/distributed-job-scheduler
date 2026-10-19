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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
// Keeping polling separate from scheduling makes the worker easy to run deterministically in tests.
public class JobWorker {
  private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
  private final JobRepository jobRepository;
  private final JobHandlerRegistry registry;
  private final ObjectMapper objectMapper;
  private final String workerId;
  private final int batchSize;
  private final int leaseSeconds;
  private final boolean heartbeatEnabled;
  private final MeterRegistry meterRegistry;
  private final Set<Long> inFlightJobIds = ConcurrentHashMap.newKeySet();
  private ScheduledExecutorService heartbeatExecutor;

  // Configuration is read once so every loop uses the same worker identity and batch policy.
  public JobWorker(
      JobRepository jobRepository,
      JobHandlerRegistry jobHandlerRegistry,
      ObjectMapper objectMapper,
      Environment environment,
      MeterRegistry meterRegistry) {
    this.jobRepository = jobRepository;
    registry = jobHandlerRegistry;
    this.objectMapper = objectMapper;
    workerId = environment.getProperty("worker.id", "worker-1");
    batchSize = environment.getProperty("worker.batch-size", Integer.class, 5);
    leaseSeconds = environment.getProperty("worker.lease-seconds", Integer.class, 30);
    heartbeatEnabled = environment.getProperty("worker.heartbeat-enabled", Boolean.class, true);
    this.meterRegistry = meterRegistry;
  }

  // Continuing until an empty claim drains available work without adding queue latency between batches.
  public void pollOnce() {
    while (true) {
      // An interrupt lets shutdown stop before claiming more work, avoiding new orphaned jobs.
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
      List<Job> jobs = jobRepository.claimBatch(workerId, batchSize);
      if (jobs.isEmpty()) {
        return;
      }
      for (Job job : jobs) {
        counter("jobs_claimed_total", "job_type", job.jobType()).increment();
      }
      startHeartbeat();
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<?>> tasks = new ArrayList<>();
        for (Job job : jobs) {
          inFlightJobIds.add(job.id());
          tasks.add(executor.submit(() -> execute(job)));
        }
        for (Future<?> task : tasks) {
          try {
            task.get();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return;
          } catch (Exception exception) {
            log.warn("Worker task failed", exception);
          }
        }
      }
    }
  }

  private void startHeartbeat() {
    if (!heartbeatEnabled || heartbeatExecutor != null) {
      return;
    }
    heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "job-heartbeat-" + workerId);
      thread.setDaemon(true);
      return thread;
    });
    heartbeatExecutor.scheduleAtFixedRate(
        () -> renewLeases(),
        Math.max(1, leaseSeconds / 3),
        Math.max(1, leaseSeconds / 3),
        TimeUnit.SECONDS);
  }

  private void renewLeases() {
    List<Long> jobIds = List.copyOf(inFlightJobIds);
    int renewedRows = jobRepository.heartbeat(workerId, jobIds, leaseSeconds);
    if (renewedRows < jobIds.size()) {
      List<Long> currentJobIds = List.copyOf(inFlightJobIds);
      List<Long> lostJobIds = new ArrayList<>(currentJobIds);
      lostJobIds.removeAll(jobRepository.runningJobIds(workerId, currentJobIds));
      if (!lostJobIds.isEmpty()) {
        log.warn("Worker {} lost leases for jobs {}", workerId, lostJobIds);
      }
    }
  }

  @PreDestroy
  public void shutdown() {
    stopHeartbeat();
  }

  private void stopHeartbeat() {
    if (heartbeatExecutor != null) {
      heartbeatExecutor.shutdownNow();
      heartbeatExecutor = null;
    }
    inFlightJobIds.clear();
  }

  // One bad job is isolated so it cannot stop the worker loop from processing other jobs.
  private void execute(Job job) {
    Timer.Sample timerSample = Timer.start(meterRegistry);
    try {
      var handler =
          registry
              .find(job.jobType())
              .orElseThrow(() -> new NonRetryableJobException("Unknown job type: " + job.jobType()));
      handler.execute(new JobContext(job.id(), job.attempts(), objectMapper.readTree(job.payload())));
      jobRepository.success(job.id(), workerId);
      recordCompletion(job, "success", timerSample);
    } catch (NonRetryableJobException exception) {
      log.warn("Job {} is not retryable: {}", job.id(), exception.getMessage());
      jobRepository.recordFailure(job, exception.getMessage(), false, workerId);
      recordCompletion(job, "dead", timerSample);
    } catch (Exception exception) {
      log.warn("Job {} failed", job.id(), exception);
      jobRepository.recordFailure(job, exception.getMessage(), true, workerId);
      recordCompletion(job, job.attempts() < job.maxAttempts() ? "retry" : "dead", timerSample);
    } finally {
      inFlightJobIds.remove(job.id());
    }
  }

  private void recordCompletion(Job job, String result, Timer.Sample timerSample) {
    counter("jobs_completed_total", "job_type", job.jobType(), "result", result).increment();
    timerSample.stop(Timer.builder("job_execution_seconds")
        .tag("job_type", job.jobType())
        .tag("result", result)
        .description("Job handler execution time")
        .publishPercentileHistogram()
        .register(meterRegistry));
  }

  private Counter counter(String name, String... tags) {
    return Counter.builder(name).tags(tags).register(meterRegistry);
  }
}
