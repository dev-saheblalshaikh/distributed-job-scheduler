package com.example.jobs.worker;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ChaosJobHandler implements JobHandler {
  public String type() {
    return "CHAOS";
  }

  public void execute(JobContext context) {
    JsonNode payload = context.payload();
    if (payload != null && payload.path("fail").asBoolean(false))
      throw new IllegalStateException("chaos requested failure");
    int failUntilAttempt = payload == null ? 0 : payload.path("failUntilAttempt").asInt(0);
    if (context.attempt() < failUntilAttempt)
      throw new IllegalStateException("chaos requested attempt failure");
    long seconds = payload == null ? 0 : payload.path("sleepSeconds").asLong(0);
    long milliseconds = payload == null ? 0 : payload.path("sleepMillis").asLong(0);
    try {
      Thread.sleep(seconds * 1000 + milliseconds);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", exception);
    }
  }
}
