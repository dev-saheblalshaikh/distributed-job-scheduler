package com.example.jobs.api;
import com.example.jobs.common.*; import org.springframework.stereotype.Service; import tools.jackson.databind.ObjectMapper; import java.time.*; import java.util.*;
@Service public class JobService { private final JobRepository repo; private final ObjectMapper json; public JobService(JobRepository repo,ObjectMapper json){this.repo=repo;this.json=json;}
 public Job create(JobDtos.CreateJobRequest r){try{return repo.byKey(r.idempotencyKey()).orElseGet(()->repo.create(r.jobType(),json.writeValueAsString(r.payload()),r.runAt()==null?LocalDateTime.now():r.runAt(),r.idempotencyKey()));}catch(Exception e){throw new IllegalArgumentException("Payload must be valid JSON",e);}}
 public Job get(long id){return repo.find(id).orElseThrow(()->new NoSuchElementException("Job not found: "+id));} public List<Job> list(JobStatus s,int limit,int offset){return repo.list(s,limit,offset);}
}
