package com.example.jobs.worker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;

class JobHandlerRegistryTest {
  @Test
  void findsHandlerByType() {
    var h =
        new JobHandler() {
          public String type() {
            return "X";
          }

          public void execute(JobContext context) {}
        };
    assertSame(h, new JobHandlerRegistry(List.of(h)).find("X").orElseThrow());
  }
}
