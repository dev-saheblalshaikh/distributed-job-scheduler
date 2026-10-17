package com.example.jobs.api;

import com.example.jobs.common.Job;
import com.example.jobs.common.JobRepository;
import com.example.jobs.common.JobStatus;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class JobService {
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public JobService(JobRepository jobRepository, ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    public Job create(JobDtos.CreateJobRequest request) {
        Job existingJob = jobRepository.byKey(request.idempotencyKey()).orElse(null);
        if (existingJob != null) { return existingJob; }
        String payload;
        try { payload = objectMapper.writeValueAsString(request.payload()); }
        catch (JacksonException exception) { throw new IllegalArgumentException("Payload must be valid JSON", exception); }
        try { return jobRepository.create(request.jobType(), payload, request.runAt() == null ? LocalDateTime.now(ZoneOffset.UTC) : request.runAt(), request.idempotencyKey()); }
        catch (DuplicateKeyException exception) { return jobRepository.byKey(request.idempotencyKey()).orElseThrow(); }
    }

    public Job get(long id) { return jobRepository.find(id).orElseThrow(() -> new NoSuchElementException("Job not found: " + id)); }
    public java.util.Optional<Job> findByIdempotencyKey(String key) { return jobRepository.byKey(key); }
    public List<Job> list(JobStatus status, int limit, int offset) { return jobRepository.list(status, limit, offset); }
}
