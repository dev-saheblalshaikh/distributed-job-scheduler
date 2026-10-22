package com.example.jobs.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoCleanup {
  private final DemoRepository demoRepository;

  public DemoCleanup(DemoRepository demoRepository) { this.demoRepository = demoRepository; }

  @Scheduled(fixedDelayString = "${demo.cleanup-interval-ms:300000}")
  public void cleanup() { demoRepository.cleanup(); }
}
