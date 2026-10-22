package com.example.jobs.demo;

import com.example.jobs.common.Job;
import com.example.jobs.common.JobRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DemoService {
  private final JobRepository jobRepository;
  private final DemoRepository demoRepository;
  private final ObjectMapper objectMapper;

  public DemoService(JobRepository jobRepository, DemoRepository demoRepository, ObjectMapper objectMapper) {
    this.jobRepository = jobRepository;
    this.demoRepository = demoRepository;
    this.objectMapper = objectMapper;
  }

  public List<Job> createScenario(String name) {
    ObjectNode payload = objectMapper.createObjectNode();
    int count;
    int maxAttempts = 3;
    switch (name) {
      case "burst" -> { count = 10; payload.put("sleepMillis", 500); }
      case "flaky" -> { count = 1; maxAttempts = 4; payload.put("failUntilAttempt", 3); }
      case "poison" -> { count = 1; payload.put("fail", true); }
      case "slow" -> { count = 1; payload.put("sleepSeconds", 20); }
      default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown demo scenario");
    }
    try {
      String payloadJson = objectMapper.writeValueAsString(payload);
      java.util.ArrayList<Job> jobs = new java.util.ArrayList<>();
      for (int index = 0; index < count; index++) {
        jobs.add(jobRepository.create("CHAOS", payloadJson, LocalDateTime.now(ZoneOffset.UTC), null, maxAttempts, "demo"));
      }
      return jobs;
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException("Could not create demo payload", exception);
    }
  }

  public Job retry(long id) {
    Job job = jobRepository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo job not found"));
    if (!"demo".equals(job.origin())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Demo job not found");
    }
    if (!demoRepository.retryDemo(id)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Demo job is not DEAD");
    }
    return jobRepository.find(id).orElseThrow();
  }

  public Map<String, Object> state() {
    java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>();
    response.put("countsByStatus", demoRepository.counts());
    response.put("oldestPendingAgeSeconds", demoRepository.oldestPendingAgeSeconds());
    response.put("jobs", demoRepository.stateRows());
    return response;
  }
}
