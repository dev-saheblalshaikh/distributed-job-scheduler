package com.example.jobs.api;

import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  record ErrorResponse(String timestamp, int status, String error, String message) {}

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<ErrorResponse> notFound(Exception exception) {
    return error(404, "Not Found", exception.getMessage());
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ErrorResponse> responseStatus(ResponseStatusException exception) {
    return error(exception.getStatusCode().value(), exception.getStatusCode().toString(), exception.getReason());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> bad(Exception exception) {
    return error(400, "Bad Request", exception.getMessage());
  }

  private ResponseEntity<ErrorResponse> error(int status, String error, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(Instant.now().toString(), status, error, message));
  }
}
