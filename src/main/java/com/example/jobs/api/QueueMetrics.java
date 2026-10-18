package com.example.jobs.api;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "api.enabled", havingValue = "true", matchIfMissing = true)
public class QueueMetrics {
  private final JdbcTemplate jdbcTemplate;
  private final Map<String, Double> queueDepth = new ConcurrentHashMap<>();
  private volatile double oldestPendingAgeSeconds;

  public QueueMetrics(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
    this.jdbcTemplate = jdbcTemplate;
    for (String status : new String[] {"PENDING", "RUNNING", "DEAD", "SUCCESS"}) {
      Gauge.builder("jobs_queue_depth", queueDepth, values -> values.getOrDefault(status, 0.0))
          .tag("status", status)
          .description("Current number of jobs by status")
          .register(meterRegistry);
    }
    Gauge.builder("jobs_oldest_pending_age_seconds", this, metrics -> metrics.oldestPendingAgeSeconds)
        .description("Age of the oldest pending job")
        .register(meterRegistry);
    refresh();
  }

  @Scheduled(fixedDelay = 5000)
  public void refresh() {
    Map<String, Integer> counts = new ConcurrentHashMap<>();
    LocalDateTime oldestPending = null;
    var rows = jdbcTemplate.queryForList(
        "select status,count(*) as job_count,min(case when status='PENDING' then run_at end) as oldest_pending from jobs group by status");
    for (Map<String, Object> row : rows) {
      String status = (String) row.get("status");
      counts.put(status, ((Number) row.get("job_count")).intValue());
      if (row.get("oldest_pending") != null) {
        oldestPending = row.get("oldest_pending") instanceof LocalDateTime
            ? (LocalDateTime) row.get("oldest_pending")
            : ((java.sql.Timestamp) row.get("oldest_pending")).toLocalDateTime();
      }
    }
    for (String status : new String[] {"PENDING", "RUNNING", "DEAD", "SUCCESS"}) {
      queueDepth.put(status, counts.getOrDefault(status, 0).doubleValue());
    }
    oldestPendingAgeSeconds = oldestPending == null
        ? 0
        : Math.max(0, Duration.between(oldestPending, LocalDateTime.now(ZoneOffset.UTC)).toSeconds());
  }
}
