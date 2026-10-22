package com.example.jobs.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoCleanup {
  private final DemoRepository demoRepository;
  private final DemoRateLimiter demoRateLimiter;

  public DemoCleanup(DemoRepository demoRepository, DemoRateLimiter demoRateLimiter) {
    this.demoRepository = demoRepository;
    this.demoRateLimiter = demoRateLimiter;
  }

  @Scheduled(fixedDelayString = "${demo.cleanup-interval-ms:300000}")
  public void cleanup() {
    demoRepository.cleanup();
    demoRateLimiter.sweep();
  }
}
