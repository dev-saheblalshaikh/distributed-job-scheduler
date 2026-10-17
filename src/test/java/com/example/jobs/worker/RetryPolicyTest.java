package com.example.jobs.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  @Test
  void growsExponentially() {
    RetryPolicy policy = new RetryPolicy(2, 300, () -> 0.5);
    assertEquals(Duration.ofSeconds(2), policy.delayForAttempt(1));
    assertEquals(Duration.ofSeconds(4), policy.delayForAttempt(2));
    assertEquals(Duration.ofSeconds(8), policy.delayForAttempt(3));
  }

  @Test
  void respectsMaximum() {
    RetryPolicy policy = new RetryPolicy(2, 10, () -> 0.5);
    assertEquals(Duration.ofSeconds(10), policy.delayForAttempt(10));
  }

  @Test
  void keepsJitterWithinTwentyPercent() {
    RetryPolicy low = new RetryPolicy(100, 300, () -> 0.0);
    RetryPolicy high = new RetryPolicy(100, 300, () -> 1.0);
    assertEquals(Duration.ofSeconds(80), low.delayForAttempt(1));
    assertEquals(Duration.ofSeconds(120), high.delayForAttempt(1));
    assertTrue(low.delayForAttempt(1).compareTo(high.delayForAttempt(1)) < 0);
  }
}
