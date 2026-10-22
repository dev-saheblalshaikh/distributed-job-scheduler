package com.example.jobs.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobs.common.JobStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
    "demo.enabled=true",
    "worker.enabled=false",
    "demo.per-client-burst-seconds=0",
    "demo.per-client-hourly-limit=100"
})
@AutoConfigureMockMvc
class DemoIntegrationTest {
  @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
  }

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clearJobs() {
    jdbcTemplate.update("delete from jobs");
  }

  @Test
  void scenariosCreateExpectedDemoJobs() throws Exception {
    mockMvc.perform(post("/demo/scenarios/burst")).andExpect(status().isAccepted());
    assertEquals(10, count("select count(*) from jobs where origin='demo'"));
    assertEquals(10, count("select count(*) from jobs where origin='demo' and job_type='CHAOS' and payload like '%sleepMillis%500%'"));
    mockMvc.perform(post("/demo/scenarios/flaky")).andExpect(status().isAccepted());
    mockMvc.perform(post("/demo/scenarios/poison")).andExpect(status().isAccepted());
    mockMvc.perform(post("/demo/scenarios/slow")).andExpect(status().isAccepted());
    assertEquals(13, count("select count(*) from jobs where origin='demo'"));
  }

  @Test
  void unknownScenarioReturnsNotFound() throws Exception {
    mockMvc.perform(post("/demo/scenarios/nope")).andExpect(status().isNotFound());
  }

  @Test
  void stateOnlyContainsDemoJobsAndTruncatesErrors() throws Exception {
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,last_error,origin,created_at,updated_at) values('EMAIL','{}','SUCCESS',1,3,UTC_TIMESTAMP(),repeat('x',300),'api',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    mockMvc.perform(post("/demo/scenarios/poison")).andExpect(status().isAccepted());
    assertEquals(1, count("select count(*) from jobs where origin='demo'"));
    assertEquals(0, count("select count(*) from jobs where origin='demo' and origin='api'"));
  }

  @Test
  void cleanupKeepsFreshDemoAndAllApiRows() {
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,origin,created_at,updated_at) values('CHAOS','{}','SUCCESS',1,3,UTC_TIMESTAMP(),'demo',UTC_TIMESTAMP()-interval 2 hour,UTC_TIMESTAMP()-interval 2 hour)");
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,origin,created_at,updated_at) values('EMAIL','{}','SUCCESS',1,3,UTC_TIMESTAMP(),'api',UTC_TIMESTAMP()-interval 2 hour,UTC_TIMESTAMP()-interval 2 hour)");
    assertTrue(new DemoRepository(jdbcTemplate, null).cleanup() > 0);
    assertEquals(1, count("select count(*) from jobs where origin='api'"));
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
