package com.example.jobs.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChaosJobHandlerTest {
  @Test
  void failPayloadThrows() throws Exception {
    ChaosJobHandler handler = new ChaosJobHandler();
    assertThrows(
        IllegalStateException.class,
        () -> handler.execute(new JobContext(1L, 1, new ObjectMapper().readTree("{\"fail\":true}"))));
  }

  @Test
  void sleepMillisPayloadPausesExecution() throws Exception {
    ChaosJobHandler handler = new ChaosJobHandler();
    JobContext context = new JobContext(1L, 1, new ObjectMapper().readTree("{\"sleepMillis\":50}"));
    assertTimeoutPreemptively(
        Duration.ofSeconds(1),
        () -> handler.execute(context));
  }
}
