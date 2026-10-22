package com.example.jobs.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobs.AbstractMysqlIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"demo.enabled=false", "worker.enabled=false"})
@AutoConfigureMockMvc
class DemoDisabledIntegrationTest extends AbstractMysqlIT {
  @Autowired MockMvc mockMvc;

  @Test
  void demoRoutesAreNotMappedWhenDisabled() throws Exception {
    mockMvc.perform(get("/demo/state")).andExpect(status().isNotFound());
  }
}
