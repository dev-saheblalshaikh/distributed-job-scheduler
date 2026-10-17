package com.example.jobs.worker;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class JobHandlerRegistry {
  private final Map<String, JobHandler> handlers;

  public JobHandlerRegistry(List<JobHandler> list) {
    handlers = new HashMap<>();
    list.forEach(h -> handlers.put(h.type(), h));
  }

  public Optional<JobHandler> find(String type) {
    return Optional.ofNullable(handlers.get(type));
  }
}
