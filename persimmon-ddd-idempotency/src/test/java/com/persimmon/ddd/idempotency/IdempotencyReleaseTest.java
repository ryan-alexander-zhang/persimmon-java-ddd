package com.persimmon.ddd.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.idempotency.IdempotencyStore.Key;
import com.persimmon.ddd.web.UnavailableDependencies;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

/** A claim that may have landed without reaching the handler, or a failed release, is released later. */
@SpringBootTest(classes = IdempotencyTest.App.class,
    properties = "spring.datasource.url=jdbc:h2:mem:idem-release;DB_CLOSE_DELAY=-1")
class IdempotencyReleaseTest {
  @Autowired ObjectMapper json;
  @Autowired IdempotencyMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private MybatisIdempotencyStore store;
  private final AtomicInteger executed = new AtomicInteger();
  private final FilterChain handler = (r, s) -> {
    executed.incrementAndGet();
    s.getWriter().write("sent");
  };

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM persimmon_idempotency");
    store = new MybatisIdempotencyStore(mapper, json, Clock.systemUTC());
  }

  @Test
  void aClaimThatLandedButThrewIsReleasedAndTheKeyRunsAgain() throws Exception {
    MybatisIdempotencyStore lost = spy(store);
    doAnswer(call -> {
      call.callRealMethod();
      throw new RuntimeException(new SQLTransientConnectionException("acknowledgement lost"));
    }).when(lost).claim(any(), any(), any(), any());
    ReleaseQueue releases = new ReleaseQueue(store);

    MockHttpServletResponse unavailable = invoke(lost, releases, ResponseRetention.DEFAULT);
    assertEquals(503, unavailable.getStatus());
    assertEquals("PENDING", state());
    assertEquals(1, releases.size());

    releases.drain();
    assertEquals(0, rows());
    assertEquals(0, releases.size());
    MockHttpServletResponse retry = invoke(store, releases, ResponseRetention.DEFAULT);
    assertEquals("sent", retry.getContentAsString());
    assertEquals(1, executed.get());
  }

  @Test
  void anyClaimFailureIsQueuedEvenWhenItEscapes() {
    MybatisIdempotencyStore lost = spy(store);
    doAnswer(call -> {
      call.callRealMethod();
      throw new IllegalStateException("not a connection problem");
    }).when(lost).claim(any(), any(), any(), any());
    ReleaseQueue releases = new ReleaseQueue(store);
    assertThrows(IllegalStateException.class, () -> invoke(lost, releases, ResponseRetention.DEFAULT));
    releases.drain();
    assertEquals(0, rows());
    assertEquals(0, executed.get());
  }

  @Test
  void aReleaseThatFailedAfterAnUnretainedAnswerIsRetried() throws Exception {
    MybatisIdempotencyStore flaky = spy(store);
    AtomicInteger abandons = new AtomicInteger();
    doAnswer(call -> {
      if (abandons.incrementAndGet() == 1) {
        throw new IllegalStateException("store down");
      }
      return call.callRealMethod();
    }).when(flaky).abandon(any(), any());
    ReleaseQueue releases = new ReleaseQueue(flaky);

    MockHttpServletResponse response = invoke(flaky, releases, ignored -> false);
    assertEquals("sent", response.getContentAsString());
    assertEquals("PENDING", state());
    assertEquals(1, releases.size());

    releases.drain();
    assertEquals(0, rows());
    assertEquals(0, releases.size());
  }

  @Test
  void aDrainStopsAtTheFirstFailureAndKeepsEveryRelease() {
    IdempotencyStore down = mock(IdempotencyStore.class);
    doThrow(new IllegalStateException("store down")).when(down).abandon(any(), any());
    ReleaseQueue releases = new ReleaseQueue(down);
    releases.add(new Key("__root__", "", "a"), "t-a");
    releases.add(new Key("__root__", "", "b"), "t-b");
    releases.drain();
    assertEquals(2, releases.size());
    verify(down, times(1)).abandon(any(), any());
  }

  private MockHttpServletResponse invoke(IdempotencyStore target, ReleaseQueue releases, ResponseRetention retention)
      throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter(target,
        new IdempotencyProperties(true, "Idempotency-Key", Duration.ofHours(24), Duration.ofMinutes(1), false,
            List.of("POST"), DataSize.ofKilobytes(1), List.of(), List.of(), List.of()),
        json, retention, List.of(), new UnavailableDependencies(t -> false, t -> null, Duration.ofSeconds(1)), releases);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/messages");
    request.addHeader("Idempotency-Key", "k-1");
    request.setContent("{}".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, handler);
    return response;
  }

  private int rows() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM persimmon_idempotency", Integer.class);
  }

  private String state() {
    return jdbc.queryForObject("SELECT state FROM persimmon_idempotency", String.class);
  }
}
