package com.example.jobs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.jobs.common.Job;
import com.example.jobs.common.JobRepository;
import com.example.jobs.common.JobStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JobServiceTest {
  @Test
  void idempotencyReturnsExisting() {
    var r = mock(JobRepository.class);
    var j =
        new Job(
            1L,
            "EMAIL",
            "{}",
            JobStatus.PENDING,
            0,
            3,
            LocalDateTime.now(),
            null,
            null,
            null,
            null,
            "same",
            LocalDateTime.now(),
            LocalDateTime.now(),
            "api");
    when(r.byKey("same")).thenReturn(java.util.Optional.of(j));
    var s = new JobService(r, new ObjectMapper());
    assertEquals(
        j,
        s.create(
            new JobDtos.CreateJobRequest(
                "EMAIL", new ObjectMapper().createObjectNode(), null, "same", 3)));
    verify(r, never()).create(any(), any(), any(), any(), anyInt());
  }
}
