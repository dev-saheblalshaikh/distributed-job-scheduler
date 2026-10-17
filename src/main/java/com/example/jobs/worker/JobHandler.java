package com.example.jobs.worker;
import tools.jackson.databind.JsonNode;
public interface JobHandler { String type(); void execute(JsonNode payload); }
