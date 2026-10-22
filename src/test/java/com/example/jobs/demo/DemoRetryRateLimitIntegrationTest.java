package com.example.jobs.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    "demo.per-client-hourly-limit=1"
})
@AutoConfigureMockMvc
class DemoRetryRateLimitIntegrationTest extends AbstractMysqlIT {
  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clearJobs() {
    jdbcTemplate.update("delete from jobs");
  }

  @Test
  void retryIsRateLimitedAfterClientUsesItsScenarioCall() throws Exception {
    mockMvc.perform(post("/demo/scenarios/poison"))
        .andExpect(status().isAccepted());

    long jobId = jdbcTemplate.queryForObject("select max(id) from jobs where origin='demo'", Long.class);

    mockMvc.perform(post("/demo/jobs/" + jobId + "/retry"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "3600"));
  }
}
