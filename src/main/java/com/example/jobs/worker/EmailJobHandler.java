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

    public void execute(JsonNode p) {
        log.info("sending email to {}", p == null ? null : p.path("to").asText());
    }
}
