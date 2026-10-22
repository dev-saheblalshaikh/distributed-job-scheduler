package com.example.jobs.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.example.jobs.AbstractMysqlIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "demo.enabled=true",
    "worker.enabled=false",
    "demo.per-client-burst-seconds=0",
    "demo.per-client-hourly-limit=100"
})
@AutoConfigureMockMvc
class DemoIntegrationTest extends AbstractMysqlIT {

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
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,last_error,origin,created_at,updated_at) values('CHAOS','{}','DEAD',3,3,UTC_TIMESTAMP(),repeat('x',300),'demo',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/demo/state"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs.length()", org.hamcrest.Matchers.is(1)))
        .andExpect(jsonPath("$.jobs[0].lastError", org.hamcrest.Matchers.hasLength(200)));
  }

  @Test
  void cleanupKeepsFreshDemoAndAllApiRows() {
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,origin,created_at,updated_at) values('CHAOS','{}','SUCCESS',1,3,UTC_TIMESTAMP(),'demo',UTC_TIMESTAMP()-interval 2 hour,UTC_TIMESTAMP()-interval 2 hour)");
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,origin,created_at,updated_at) values('EMAIL','{}','SUCCESS',1,3,UTC_TIMESTAMP(),'api',UTC_TIMESTAMP()-interval 2 hour,UTC_TIMESTAMP()-interval 2 hour)");
    jdbcTemplate.update("insert into jobs(job_type,payload,status,attempts,max_attempts,run_at,origin,created_at,updated_at) values('CHAOS','{}','SUCCESS',1,3,UTC_TIMESTAMP(),'demo',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
    assertTrue(new DemoRepository(jdbcTemplate, null).cleanup() > 0);
    assertEquals(1, count("select count(*) from jobs where origin='api'"));
    assertEquals(1, count("select count(*) from jobs where origin='demo'"));
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
