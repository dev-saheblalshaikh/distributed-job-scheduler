package com.example.jobs.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EmailJobHandler implements JobHandler {

  private static final Logger log = LoggerFactory.getLogger(EmailJobHandler.class);

  public String type() {
    return "EMAIL";
  }

  public void execute(JobContext context) {
    JsonNode payload = context.payload();
    log.info("sending email to {}", payload == null ? null : payload.path("to").asText());
  }
}
