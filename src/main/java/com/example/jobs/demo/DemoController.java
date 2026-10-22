package com.example.jobs.demo;

import com.example.jobs.common.Job;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo")
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoController {
  private final DemoService demoService;
  private final DemoRepository demoRepository;
  private final DemoProperties properties;
  private final Counter rateLimitCounter;
  private final Counter capacityCounter;
  private final ConcurrentHashMap<String, ArrayDeque<Instant>> calls = new ConcurrentHashMap<>();

  public DemoController(DemoService demoService, DemoRepository demoRepository, DemoProperties properties,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.demoService = demoService;
    this.demoRepository = demoRepository;
    this.properties = properties;
    rateLimitCounter = Counter.builder("demo_rejected_total").description("Rejected demo requests")
        .tag("reason", "rate_limit").register(meterRegistry);
    capacityCounter = Counter.builder("demo_rejected_total").description("Rejected demo requests")
        .tag("reason", "capacity").register(meterRegistry);
  }

  @PostMapping("/scenarios/{name}")
  public ResponseEntity<?> scenario(@PathVariable String name, HttpServletRequest request) {
    String client = clientId(request);
    Instant now = Instant.now();
    ArrayDeque<Instant> history = calls.computeIfAbsent(client, ignored -> new ArrayDeque<>());
    synchronized (history) {
      history.removeIf(value -> value.isBefore(now.minus(Duration.ofHours(1))));
      if ((!history.isEmpty() && history.peekLast().isAfter(now.minusSeconds(properties.getPerClientBurstSeconds())))
          || history.size() >= properties.getPerClientHourlyLimit()) {
        rateLimitCounter.increment();
        long retryAfter = history.size() >= properties.getPerClientHourlyLimit() ? 3600 : properties.getPerClientBurstSeconds();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", String.valueOf(retryAfter))
            .body(Map.of("message", "Demo rate limit exceeded"));
      }
      if (demoRepository.activeCount() >= properties.getGlobalPendingRunningCap()) {
        capacityCounter.increment();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Demo capacity is full"));
      }
      history.addLast(now);
    }
    return ResponseEntity.accepted().body(demoService.createScenario(name).stream().map(Job::id).toList());
  }

  @PostMapping("/jobs/{id}/retry")
  public Job retry(@PathVariable long id) { return demoService.retry(id); }

  @GetMapping("/state")
  public Map<String, Object> state() { return demoService.state(); }

  private String clientId(HttpServletRequest request) {
    if (properties.isTrustProxyHeader()) {
      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        String[] hops = forwarded.split(",");
        return hops[hops.length - 1].trim();
      }
    }
    return request.getRemoteAddr();
  }
}
