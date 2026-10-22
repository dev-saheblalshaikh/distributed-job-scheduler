package com.example.jobs.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo")
@ConditionalOnProperty(name = "demo.enabled", havingValue = "false", matchIfMissing = true)
public class DemoDisabledController {
  @RequestMapping("/**")
  public ResponseEntity<Void> notFound() {
    return ResponseEntity.notFound().build();
  }
}
