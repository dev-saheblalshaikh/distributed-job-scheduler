package com.example.jobs.demo;

import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class DemoRateLimiter {
  public enum Decision { ALLOWED, RATE_LIMITED, CAPACITY }

  private final DemoRepository demoRepository;
  private final DemoProperties properties;
  private final Counter rateLimitCounter;
  private final Counter capacityCounter;
  private final ConcurrentHashMap<String, ArrayDeque<Instant>> calls = new ConcurrentHashMap<>();

  public DemoRateLimiter(DemoRepository demoRepository, DemoProperties properties,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.demoRepository = demoRepository;
    this.properties = properties;
    rateLimitCounter = Counter.builder("demo_rejected_total").description("Rejected demo requests")
        .tag("reason", "rate_limit").register(meterRegistry);
    capacityCounter = Counter.builder("demo_rejected_total").description("Rejected demo requests")
        .tag("reason", "capacity").register(meterRegistry);
  }

  public Decision check(HttpServletRequest request) {
    String client = clientId(request);
    Instant now = Instant.now();
    ArrayDeque<Instant> history = calls.get(client);
    if (history == null) {
      if (calls.size() >= 10000) {
        rateLimitCounter.increment();
        return Decision.RATE_LIMITED;
      }
      ArrayDeque<Instant> created = new ArrayDeque<>();
      history = calls.putIfAbsent(client, created);
      if (history == null) {
        history = created;
      }
    }
    synchronized (history) {
      history.removeIf(value -> value.isBefore(now.minus(Duration.ofHours(1))));
      if ((!history.isEmpty() && history.peekLast().isAfter(now.minusSeconds(properties.getPerClientBurstSeconds())))
          || history.size() >= properties.getPerClientHourlyLimit()) {
        rateLimitCounter.increment();
        return Decision.RATE_LIMITED;
      }
      if (demoRepository.activeCount() >= properties.getGlobalPendingRunningCap()) {
        capacityCounter.increment();
        return Decision.CAPACITY;
      }
      history.addLast(now);
      return Decision.ALLOWED;
    }
  }

  public long retryAfter(HttpServletRequest request) {
    ArrayDeque<Instant> history = calls.get(clientId(request));
    if (history != null) {
      synchronized (history) {
        if (history.size() >= properties.getPerClientHourlyLimit()) {
          return 3600;
        }
      }
    }
    return properties.getPerClientBurstSeconds();
  }

  public void sweep() {
    Instant cutoff = Instant.now().minus(Duration.ofHours(1));
    calls.entrySet().removeIf(entry -> {
      ArrayDeque<Instant> history = entry.getValue();
      synchronized (history) {
        history.removeIf(value -> value.isBefore(cutoff));
        return history.isEmpty();
      }
    });
  }

  int trackedClients() { return calls.size(); }

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
