package com.example.jobs.worker;

import java.time.Duration;
import java.util.function.DoubleSupplier;

public class RetryPolicy {
  private final long baseDelaySeconds;
  private final long maxDelaySeconds;
  private final DoubleSupplier randomSource;

  public RetryPolicy(long baseDelaySeconds, long maxDelaySeconds, DoubleSupplier randomSource) {
    this.baseDelaySeconds = baseDelaySeconds;
    this.maxDelaySeconds = maxDelaySeconds;
    this.randomSource = randomSource;
  }

  public Duration delayForAttempt(int attempt) {
    long growth = baseDelaySeconds * (1L << Math.min(attempt - 1, 62));
    long capped = Math.min(maxDelaySeconds, growth);
    if (capped == 0) {
      return Duration.ZERO;
    }
    // Jitter prevents many workers from retrying the same failed jobs at the same instant.
    double multiplier = 0.8 + randomSource.getAsDouble() * 0.4;
    return Duration.ofMillis(Math.max(0, Math.round(capped * 1000 * multiplier)));
  }
}
