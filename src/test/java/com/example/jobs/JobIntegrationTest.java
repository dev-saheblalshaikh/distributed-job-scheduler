package com.example.jobs;

import static org.junit.jupiter.api.Assertions.*;

import com.example.jobs.api.*;
import com.example.jobs.common.*;
import com.example.jobs.worker.JobWorker;
import com.example.jobs.worker.JobHandlerRegistry;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
@SpringBootTest(properties = "worker.enabled=false")
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
                null));
    assertEquals(JobStatus.PENDING, j.status());
    worker.pollOnce();
    assertEquals(JobStatus.SUCCESS, repo.find(j.id()).orElseThrow().status());
  }

  @Test
  void threeWorkersProcessThreeHundredJobsExactlyOnce() throws Exception {
    var runAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5);
    for (int index = 0; index < 300; index++) {
      service.create(
          new JobDtos.CreateJobRequest(
              "EMAIL", objectMapper.createObjectNode().put("to", "test@example.com"), runAt, null));
    }
    var firstWorker = worker("worker-1");
    var secondWorker = worker("worker-2");
    var thirdWorker = worker("worker-3");
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
      executor.invokeAll(
          java.util.List.of(
              () -> {
                firstWorker.pollOnce();
                return null;
              },
              () -> {
                secondWorker.pollOnce();
                return null;
              },
              () -> {
                thirdWorker.pollOnce();
                return null;
              }));
    }
    assertEquals(0, jdbcTemplate.queryForObject("select count(*) from jobs where status='PENDING'", Integer.class));
    assertEquals(300, jdbcTemplate.queryForObject("select count(*) from jobs where status='SUCCESS'", Integer.class));
    assertEquals(0, jdbcTemplate.queryForObject("select count(*) from jobs where attempts<>1", Integer.class));
    assertEquals(3, jdbcTemplate.queryForObject("select count(distinct locked_by) from jobs", Integer.class));
  }

  private JobWorker worker(String workerId) {
    var environment =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("worker.id", workerId)
            .withProperty("worker.batch-size", "5");
    return new JobWorker(repo, registry, objectMapper, environment);
  }
}
