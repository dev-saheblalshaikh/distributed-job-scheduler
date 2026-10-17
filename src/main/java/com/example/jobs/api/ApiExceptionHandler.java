package com.example.jobs.api;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import java.time.*; import java.util.*;
@RestControllerAdvice public class ApiExceptionHandler { record ErrorResponse(String timestamp,int status,String error,String message){}
 @ExceptionHandler(NoSuchElementException.class) ResponseEntity<ErrorResponse> notFound(Exception e){return error(404,"Not Found",e.getMessage());}
 @ExceptionHandler(Exception.class) ResponseEntity<ErrorResponse> bad(Exception e){return error(400,"Bad Request",e.getMessage());} private ResponseEntity<ErrorResponse> error(int s,String e,String m){return ResponseEntity.status(s).body(new ErrorResponse(Instant.now().toString(),s,e,m));}}
