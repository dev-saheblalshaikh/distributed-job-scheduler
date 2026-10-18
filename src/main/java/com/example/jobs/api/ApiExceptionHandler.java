package com.example.jobs.api;

import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  record ErrorResponse(String timestamp, int status, String error, String message) {
  }

  @ExceptionHandler({
      MethodArgumentNotValidException.class,
      BindException.class,
      ConstraintViolationException.class,
      HttpMessageNotReadableException.class,
      IllegalArgumentException.class
  })
  ResponseEntity<ErrorResponse> badInput(Exception exception) {
    return error(400, "Bad Request", exception.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<ErrorResponse> notFound(NoSuchElementException exception) {
    return error(404, "Not Found", exception.getMessage());
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ErrorResponse> responseStatus(ResponseStatusException exception) {
    return error(exception.getStatusCode().value(), exception.getStatusCode().toString(), exception.getReason());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorResponse> unexpected(Exception exception) {
    log.error("Unhandled API exception", exception);
    return error(500, "Internal Server Error", "An unexpected error occurred");
  }

  private ResponseEntity<ErrorResponse> error(int status, String error, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(Instant.now().toString(), status, error, message));
  }
}
