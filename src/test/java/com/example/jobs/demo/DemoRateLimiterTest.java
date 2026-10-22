package com.example.jobs.demo;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.mock.web.MockHttpServletRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DemoRateLimiterTest {
  @Test
  void burstLimitIsRejected() {
    DemoProperties properties = properties(3, 30, 60);
    DemoRateLimiter limiter = limiter(properties, 0);
    MockHttpServletRequest request = request("1.1.1.1");
    assertEquals(DemoRateLimiter.Decision.ALLOWED, limiter.check(request));
    assertEquals(DemoRateLimiter.Decision.RATE_LIMITED, limiter.check(request));
    assertEquals(3, limiter.retryAfter(request));
  }

  @Test
  void hourlyLimitUsesLongRetryAfter() {
    DemoProperties properties = properties(0, 1, 60);
    DemoRateLimiter limiter = limiter(properties, 0);
    MockHttpServletRequest request = request("2.2.2.2");
    assertEquals(DemoRateLimiter.Decision.ALLOWED, limiter.check(request));
    assertEquals(DemoRateLimiter.Decision.RATE_LIMITED, limiter.check(request));
    assertEquals(3600, limiter.retryAfter(request));
  }

  @Test
  void capacityIsRejected() {
    DemoRateLimiter limiter = limiter(properties(0, 30, 0), 1);
    assertEquals(DemoRateLimiter.Decision.CAPACITY, limiter.check(request("3.3.3.3")));
  }

  @Test
  void clientCapRejectsTheNextNewClient() {
    DemoRateLimiter limiter = limiter(properties(0, 100000, 60), 0);
    for (int index = 0; index < 10000; index++) {
      assertEquals(DemoRateLimiter.Decision.ALLOWED, limiter.check(request("10.0.0." + index)));
    }
    assertEquals(DemoRateLimiter.Decision.RATE_LIMITED, limiter.check(request("new-client")));
  }

  @Test
  void sweepRemovesOldClients() throws Exception {
    DemoRateLimiter limiter = limiter(properties(0, 30, 60), 0);
    limiter.check(request("old-client"));
    Field field = DemoRateLimiter.class.getDeclaredField("calls");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    ConcurrentHashMap<String, ArrayDeque<Instant>> calls = (ConcurrentHashMap<String, ArrayDeque<Instant>>) field.get(limiter);
    calls.get("old-client").clear();
    calls.get("old-client").add(Instant.now().minusSeconds(3601));
    limiter.sweep();
    assertEquals(0, limiter.trackedClients());
  }

  @Test
  void forwardedAddressIsIgnoredByDefault() {
    DemoProperties properties = properties(3, 1, 60);
    DemoRateLimiter limiter = limiter(properties, 0);
    MockHttpServletRequest first = request("4.4.4.4");
    first.addHeader("X-Forwarded-For", "spoof-a");
    MockHttpServletRequest second = request("4.4.4.4");
    second.addHeader("X-Forwarded-For", "spoof-b");
    assertEquals(DemoRateLimiter.Decision.ALLOWED, limiter.check(first));
    assertEquals(DemoRateLimiter.Decision.RATE_LIMITED, limiter.check(second));
  }

  @Test
  void forwardedLastHopIsUsedWhenTrusted() {
    DemoProperties properties = properties(3, 30, 60);
    properties.setTrustProxyHeader(true);
    DemoRateLimiter limiter = limiter(properties, 0);
    MockHttpServletRequest first = request("5.5.5.5");
    first.addHeader("X-Forwarded-For", "one, last");
    MockHttpServletRequest second = request("6.6.6.6");
    second.addHeader("X-Forwarded-For", "different, last");
    assertEquals(DemoRateLimiter.Decision.ALLOWED, limiter.check(first));
    assertEquals(DemoRateLimiter.Decision.RATE_LIMITED, limiter.check(second));
  }

  private DemoRateLimiter limiter(DemoProperties properties, int activeCount) {
    DemoRepository repository = mock(DemoRepository.class);
    when(repository.activeCount()).thenReturn(activeCount);
    return new DemoRateLimiter(repository, properties, new SimpleMeterRegistry());
  }

  private DemoProperties properties(int burst, int hourly, int capacity) {
    DemoProperties properties = new DemoProperties();
    properties.setPerClientBurstSeconds(burst);
    properties.setPerClientHourlyLimit(hourly);
    properties.setGlobalPendingRunningCap(capacity);
    return properties;
  }

  private MockHttpServletRequest request(String address) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(address);
    return request;
  }
}
