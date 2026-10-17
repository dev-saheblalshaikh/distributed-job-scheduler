package com.example.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.example.jobs.api.*;
import com.example.jobs.common.*;
import com.example.jobs.worker.JobWorker;
import com.example.jobs.worker.JobHandlerRegistry;
import com.example.jobs.worker.JobContext;
import com.example.jobs.worker.JobHandler;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {"worker.enabled=false", "worker.retry.base-delay-seconds=0"})
@Import(JobIntegrationTest.BarrierHandlerConfiguration.class)
class JobIntegrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mysql::getJdbcUrl);
    r.add("spring.datasource.username", mysql::getUsername);
    r.add("spring.datasource.password", mysql::getPassword);
  }

  @Autowired JobService service;
  @Autowired JobRepository repo;
  @Autowired JobWorker worker;
  @Autowired JobHandlerRegistry registry;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  private static final CyclicBarrier barrier = new CyclicBarrier(3);

  @TestConfiguration
  static class BarrierHandlerConfiguration {
    @Bean
    JobHandler barrierJobHandler() {
      return new JobHandler() {
        public String type() {
          return "BARRIER";
        }

        public void execute(JobContext context) {
          try {
            barrier.await(15, TimeUnit.SECONDS);
          } catch (Exception exception) {
            throw new IllegalStateException("barrier did not release", exception);
          }
        }
      };
    }
  }

  @BeforeEach
  void clearJobs() {
    jdbcTemplate.update("delete from jobs");
  }

  @Test
  void submitsAndCanReachSuccess() {
    var runAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    var j =
        service.create(
            new JobDtos.CreateJobRequest(
                "EMAIL",
                new ObjectMapper().createObjectNode().put("to", "test@example.com"),
                runAt,
                null,
                3));
    assertEquals(JobStatus.PENDING, j.status());
    worker.pollOnce();
    assertEquals(JobStatus.SUCCESS, repo.find(j.id()).orElseThrow().status());
  }

  @Test
  void threeWorkersProcessThreeHundredJobsExactlyOnceWithoutDuplicates() throws Exception {
    var runAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    for (int index = 0; index < 300; index++) {
      service.create(
          new JobDtos.CreateJobRequest(
              "CHAOS", objectMapper.createObjectNode().put("sleepMillis", 50), runAt, null, 3));
    }
    var firstWorker = worker("worker-1");
    var secondWorker = worker("worker-2");
    var thirdWorker = worker("worker-3");
    CountDownLatch startLatch = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      var firstTask = executor.submit(() -> {
        startLatch.await();
        firstWorker.pollOnce();
        return null;
      });
      var secondTask = executor.submit(() -> {
        startLatch.await();
        secondWorker.pollOnce();
        return null;
      });
      var thirdTask = executor.submit(() -> {
        startLatch.await();
        thirdWorker.pollOnce();
        return null;
      });
      startLatch.countDown();
      firstTask.get();
      secondTask.get();
      thirdTask.get();
    }
    assertEquals(0, jdbcTemplate.queryForObject("select count(*) from jobs where status='PENDING'", Integer.class));
    assertEquals(300, jdbcTemplate.queryForObject("select count(*) from jobs where status='SUCCESS'", Integer.class));
    assertEquals(0, jdbcTemplate.queryForObject("select count(*) from jobs where attempts<>1", Integer.class));
  }

  @Test
  void threeWorkersRunThreeJobsInParallel() throws Exception {
    for (int index = 0; index < 3; index++) {
      createJob("BARRIER", objectMapper.createObjectNode(), 1);
    }
    var firstWorker = worker("parallel-worker-1");
    var secondWorker = worker("parallel-worker-2");
    var thirdWorker = worker("parallel-worker-3");
    CountDownLatch startLatch = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      var tasks = java.util.List.of(
          executor.submit(() -> runWorker(firstWorker, startLatch)),
          executor.submit(() -> runWorker(secondWorker, startLatch)),
          executor.submit(() -> runWorker(thirdWorker, startLatch)));
      startLatch.countDown();
      for (var task : tasks) {
        try {
          task.get();
        } catch (Exception exception) {
          printJobStates();
          throw exception;
        }
      }
    }
    assertEquals(3, jdbcTemplate.queryForObject("select count(*) from jobs where status='SUCCESS'", Integer.class));
    assertEquals(3, jdbcTemplate.queryForObject("select count(distinct locked_by) from jobs", Integer.class));
  }

  private Void runWorker(JobWorker jobWorker, CountDownLatch startLatch) throws Exception {
    startLatch.await();
    jobWorker.pollOnce();
    return null;
  }

  private void printJobStates() {
    System.out.println(jdbcTemplate.queryForList("select id,status,locked_by from jobs order by id"));
  }

  @Test
  void chaosFailureBecomesDeadAfterMaximumAttempts() {
    var job = createJob("CHAOS", objectMapper.createObjectNode().put("fail", true), 3);
    worker.pollOnce();
    var storedJob = repo.find(job.id()).orElseThrow();
    assertEquals(JobStatus.DEAD, storedJob.status());
    assertEquals(3, storedJob.attempts());
    assertNotNull(storedJob.lastError());
  }

  @Test
  void chaosEventuallySucceedsOnConfiguredAttempt() {
    var job = createJob("CHAOS", objectMapper.createObjectNode().put("failUntilAttempt", 3), 3);
    worker.pollOnce();
    var storedJob = repo.find(job.id()).orElseThrow();
    assertEquals(JobStatus.SUCCESS, storedJob.status());
    assertEquals(3, storedJob.attempts());
  }

  @Test
  void unknownTypeBecomesDeadAfterOneAttempt() {
    var job = createJob("UNKNOWN", objectMapper.createObjectNode(), 3);
    worker.pollOnce();
    var storedJob = repo.find(job.id()).orElseThrow();
    assertEquals(JobStatus.DEAD, storedJob.status());
    assertEquals(1, storedJob.attempts());
  }

  @Test
  void retryRequeuesDeadJobAndRejectsNonDeadJob() {
    var deadJob = createJob("UNKNOWN", objectMapper.createObjectNode(), 3);
    worker.pollOnce();
    var requeuedJob = service.retry(deadJob.id());
    assertEquals(JobStatus.PENDING, requeuedJob.status());
    assertEquals(0, requeuedJob.attempts());
    var pendingJob = createJob("EMAIL", objectMapper.createObjectNode(), 3);
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.retry(pendingJob.id()));
  }

  private Job createJob(String type, tools.jackson.databind.JsonNode payload, int maxAttempts) {
    return service.create(
        new JobDtos.CreateJobRequest(
            type,
            payload,
            LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5),
            null,
            maxAttempts));
  }

  private JobWorker worker(String workerId) {
    var environment =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("worker.id", workerId)
            .withProperty("worker.batch-size", "1");
    return new JobWorker(repo, registry, objectMapper, environment);
  }

}
