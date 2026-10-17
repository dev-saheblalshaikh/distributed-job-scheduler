package com.example.jobs.worker;

import tools.jackson.databind.JsonNode;

public record JobContext(long jobId, int attempt, JsonNode payload) {}
