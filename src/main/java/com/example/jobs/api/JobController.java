package com.example.jobs.api;
import com.example.jobs.common.*; import jakarta.validation.Valid; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.util.*;
@RestController @RequestMapping("/api/jobs") public class JobController { private final JobService service; public JobController(JobService service){this.service=service;}
 @PostMapping public ResponseEntity<JobDtos.JobResponse> create(@Valid @RequestBody JobDtos.CreateJobRequest r){return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(service.create(r)));}
 @GetMapping("/{id}") public JobDtos.JobResponse get(@PathVariable long id){return toResponse(service.get(id));}
 @GetMapping public List<JobDtos.JobResponse> list(@RequestParam(required=false) JobStatus status,@RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset){return service.list(status,Math.min(limit,100),Math.max(offset,0)).stream().map(JobController::toResponse).toList();}
 static JobDtos.JobResponse toResponse(Job j){try{return new JobDtos.JobResponse(j.id(),j.jobType(),new tools.jackson.databind.ObjectMapper().readTree(j.payload()),j.status().name(),j.attempts(),j.maxAttempts(),j.runAt(),j.lastError(),j.idempotencyKey());}catch(Exception e){throw new IllegalStateException("Stored payload is invalid",e);}}
}
