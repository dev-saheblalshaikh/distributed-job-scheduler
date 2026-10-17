package com.example.jobs.worker;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
// A registry lets new job types join dispatch through one component without changing worker logic.
public class JobHandlerRegistry {
  private final Map<String, JobHandler> handlers;

  // Spring supplies all handlers, so registration is automatic when a handler component is added.
  public JobHandlerRegistry(List<JobHandler> list) {
    handlers = new HashMap<>();
    list.forEach(h -> handlers.put(h.type(), h));
  }

  // Optional makes unknown job types an explicit worker failure instead of a null-pointer crash.
  public Optional<JobHandler> find(String type) {
    return Optional.ofNullable(handlers.get(type));
  }
}
