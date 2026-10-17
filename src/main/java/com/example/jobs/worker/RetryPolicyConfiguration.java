package com.example.jobs.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetryPolicyConfiguration {
  @Bean
  RetryPolicy retryPolicy(org.springframework.core.env.Environment environment) {
    long baseDelay = environment.getProperty("worker.retry.base-delay-seconds", Long.class, 2L);
    long maxDelay = environment.getProperty("worker.retry.max-delay-seconds", Long.class, 300L);
    return new RetryPolicy(baseDelay, maxDelay, Math::random);
  }
}
