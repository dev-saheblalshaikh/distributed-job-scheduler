package com.example.jobs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.jobs.common.Job;
import com.example.jobs.common.JobRepository;
import com.example.jobs.common.JobStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class JobWorkerMetricsTest {
  @Test
  void successfulAndFailingJobsUpdateCountersAndTimers() {
    JobRepository repository = mock(JobRepository.class);
    JobHandlerRegistry registry = mock(JobHandlerRegistry.class);
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    Job successfulJob = job(1L, "EMAIL");
    Job failingJob = job(2L, "CHAOS");
    JobHandler successfulHandler = new JobHandler() {
      public String type() { return "EMAIL"; }
      public void execute(JobContext context) { }
    };
    JobHandler failingHandler = new JobHandler() {
      public String type() { return "CHAOS"; }
      public void execute(JobContext context) { throw new IllegalStateException("failure"); }
    };
    when(repository.claimBatch("test-worker", 5)).thenReturn(List.of(successfulJob), List.of(failingJob), List.of());
    when(registry.find("EMAIL")).thenReturn(Optional.of(successfulHandler));
    when(registry.find("CHAOS")).thenReturn(Optional.of(failingHandler));
    JobWorker worker = new JobWorker(repository, registry, new ObjectMapper(),
        new MockEnvironment().withProperty("worker.id", "test-worker"), meterRegistry);

    worker.pollOnce();

    assertEquals(1.0, meterRegistry.get("jobs_claimed_total").tag("job_type", "EMAIL").counter().count());
    assertEquals(1.0, meterRegistry.get("jobs_claimed_total").tag("job_type", "CHAOS").counter().count());
    assertEquals(1.0, meterRegistry.get("jobs_completed_total").tags("job_type", "EMAIL", "result", "success").counter().count());
    assertEquals(1.0, meterRegistry.get("jobs_completed_total").tags("job_type", "CHAOS", "result", "retry").counter().count());
    assertEquals(1, meterRegistry.get("job_execution_seconds").tag("job_type", "EMAIL").timer().count());
    assertEquals(1, meterRegistry.get("job_execution_seconds").tag("job_type", "CHAOS").timer().count());
  }

  private Job job(Long id, String type) {
    LocalDateTime now = LocalDateTime.now();
    return new Job(id, type, "{}", JobStatus.RUNNING, 1, 3, now, "test-worker", now, now, null, "key-" + id, now, now);
  }
}
