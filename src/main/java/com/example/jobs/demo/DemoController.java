package com.example.jobs.demo;

import com.example.jobs.common.Job;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo")
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoController {
  private final DemoService demoService;
  private final DemoRateLimiter rateLimiter;

  public DemoController(DemoService demoService, DemoRateLimiter rateLimiter) {
    this.demoService = demoService;
    this.rateLimiter = rateLimiter;
  }

  @PostMapping("/scenarios/{name}")
  public ResponseEntity<?> scenario(@PathVariable String name, HttpServletRequest request) {
    DemoRateLimiter.Decision decision = rateLimiter.check(request);
    if (decision == DemoRateLimiter.Decision.RATE_LIMITED) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header("Retry-After", String.valueOf(rateLimiter.retryAfter(request)))
          .body(Map.of("message", "Demo rate limit exceeded"));
    }
    if (decision == DemoRateLimiter.Decision.CAPACITY) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Demo capacity is full"));
    }
    return ResponseEntity.accepted().body(demoService.createScenario(name).stream().map(Job::id).toList());
  }

  @PostMapping("/jobs/{id}/retry")
  public ResponseEntity<?> retry(@PathVariable long id, HttpServletRequest request) {
    DemoRateLimiter.Decision decision = rateLimiter.check(request);
    if (decision == DemoRateLimiter.Decision.RATE_LIMITED) {
      return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
          .header("Retry-After", String.valueOf(rateLimiter.retryAfter(request)))
          .body(Map.of("message", "Demo rate limit exceeded"));
    }
    if (decision == DemoRateLimiter.Decision.CAPACITY) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Demo capacity is full"));
    }
    return ResponseEntity.ok(demoService.retry(id));
  }

  @GetMapping("/state")
  public Map<String, Object> state() { return demoService.state(); }

}
