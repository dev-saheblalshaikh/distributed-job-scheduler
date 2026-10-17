package com.example.jobs.worker;


public interface JobHandler {
  String type();

  void execute(JobContext context);
}
