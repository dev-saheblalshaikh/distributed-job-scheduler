package com.example.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StaticPageTest {
  @Test
  void indexIsSelfContainedAndListsEveryScenario() throws IOException {
    String page;
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("static/index.html")) {
      assertTrue(stream != null, "static/index.html should be on the test classpath");
      page = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertFalse(page.contains("innerHTML"));
    assertFalse(page.contains("<script src"));
    String withoutGithubLink = page.replace("https://github.com/dev-saheblalshaikh/distributed-job-scheduler", "");
    assertFalse(withoutGithubLink.contains("http://"));
    assertFalse(withoutGithubLink.contains("https://"));
    assertTrue(page.contains("burst"));
    assertTrue(page.contains("flaky"));
    assertTrue(page.contains("poison"));
    assertTrue(page.contains("slow"));
  }
}
