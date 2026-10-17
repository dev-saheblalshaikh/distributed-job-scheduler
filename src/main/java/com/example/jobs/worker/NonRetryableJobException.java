package com.example.jobs.worker;

public class NonRetryableJobException extends RuntimeException {
  public NonRetryableJobException(String message) {
    super(message);
  }
}
