package com.example.jobs.worker;

import com.example.jobs.common.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JobWorker {
  private static final Logger log = LoggerFactory.getLogger(JobWorker.class);
  private final JobRepository jobRepository;
  private final JobHandlerRegistry registry;
  private final ObjectMapper objectMapper;
  private final String workerId;

  public JobWorker(
      JobRepository jobRepository,
      JobHandlerRegistry jobHandlerRegistry,
      ObjectMapper objectMapper,
      org.springframework.core.env.Environment environment) {
    this.jobRepository = jobRepository;
    registry = jobHandlerRegistry;
    this.objectMapper = objectMapper;
    workerId = environment.getProperty("worker.id", "worker-1");
  }

  public void pollOnce() {
    jobRepository.pending().stream()
        .findFirst()
        .ifPresent(
            job -> {
              if (!jobRepository.claim(job.id(), workerId)) {
                return;
              }
              try {
                var handler =
                    registry
                        .find(job.jobType())
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException("Unknown job type: " + job.jobType()));
                handler.execute(objectMapper.readTree(job.payload()));
                jobRepository.success(job.id());
              } catch (Exception exception) {
                log.warn("Job {} failed", job.id(), exception);
                jobRepository.fail(job.id(), exception.getMessage());
              }
            });
  }
}
