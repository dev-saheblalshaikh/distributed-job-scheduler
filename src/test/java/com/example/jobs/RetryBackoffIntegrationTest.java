package com.example.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.jobs.api.JobDtos;
import com.example.jobs.api.JobService;
import com.example.jobs.common.Job;
import com.example.jobs.common.JobRepository;
import com.example.jobs.common.JobStatus;
import com.example.jobs.worker.JobWorker;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
    "worker.enabled=false",
    "worker.retry.base-delay-seconds=10"
})
class RetryBackoffIntegrationTest {
  @Container
  static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
  }

  @Autowired
  private JobService jobService;

  @Autowired
  private JobRepository jobRepository;

  @Autowired
  private JobWorker jobWorker;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clearJobs() {
    jdbcTemplate.update("delete from jobs");
  }

  @Test
  void failedJobIsDelayedAndNotClaimedBeforeItsDueTime() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    Job job = jobService.create(
        new JobDtos.CreateJobRequest(
            "CHAOS",
            objectMapper.createObjectNode().put("fail", true),
            now.minusSeconds(5),
            null,
            3));

    jobWorker.pollOnce();

    Job delayedJob = jobRepository.find(job.id()).orElseThrow();
    LocalDateTime earliestExpectedRun = now.plusSeconds(7);
    LocalDateTime latestExpectedRun = now.plusSeconds(13);
    assertEquals(JobStatus.PENDING, delayedJob.status());
    assertEquals(1, delayedJob.attempts());
    assertTrue(delayedJob.runAt().isAfter(earliestExpectedRun));
    assertTrue(delayedJob.runAt().isBefore(latestExpectedRun));

    jobWorker.pollOnce();

    Job stillDelayedJob = jobRepository.find(job.id()).orElseThrow();
    assertEquals(JobStatus.PENDING, stillDelayedJob.status());
    assertEquals(1, stillDelayedJob.attempts());
  }
}
