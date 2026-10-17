package com.example.jobs.worker;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChaosJobHandlerTest {
  @Test
  void failPayloadThrows() throws Exception {
    ChaosJobHandler handler = new ChaosJobHandler();
    assertThrows(
        IllegalStateException.class,
        () -> handler.execute(new ObjectMapper().readTree("{\"fail\":true}")));
  }
}
