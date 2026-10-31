package com.persimmon.ddd.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.idempotency.IdempotencyStore.Claim;
import com.persimmon.ddd.idempotency.IdempotencyStore.Key;
import com.persimmon.ddd.idempotency.IdempotencyStore.StoredResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

/** Both request stores and the purge use the same database; only time and thread handover are controlled. */
@SpringBootTest(classes = IdempotencyTest.App.class, properties = "spring.datasource.url=jdbc:h2:mem:idem-concurrency;DB_CLOSE_DELAY=-1")
class IdempotencyConcurrencyTest {
  private static final Key KEY = new Key("__root__", "", "same-key");
  private static final Duration LEASE = Duration.ofSeconds(150);
  private static final Duration TTL = Duration.ofHours(24);
  @Autowired ObjectMapper json;
  @Autowired IdempotencyMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private TestClock clock;
  private MybatisIdempotencyStore a;
  private MybatisIdempotencyStore b;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM persimmon_idempotency");
    clock = new TestClock();
    a = new MybatisIdempotencyStore(mapper, json, clock);
    b = new MybatisIdempotencyStore(mapper, json, clock);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void purgeDoesNotDeleteAReplacementClaimOrResponse(boolean completed) throws Exception {
    Claim.Won old = won(a);
    a.complete(KEY, old.token(), response("old"), Duration.ofSeconds(1));
    clock.advance(Duration.ofSeconds(2));
    CountDownLatch selected = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    IdempotencyMapper paused = mock(IdempotencyMapper.class, delegatesTo(mapper));
    doAnswer(invocation -> {
      List<IdempotencyRecord> rows = mapper.selectList(invocation.getArgument(0));
      selected.countDown();
      assertTrue(resume.await(5, TimeUnit.SECONDS), "purge was never resumed");
      return rows;
    }).when(paused).selectList(any(Wrapper.class));
    MybatisIdempotencyStore purge = new MybatisIdempotencyStore(paused, json, clock);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var task = executor.submit(() -> purge.deleteExpired(100));
      try {
        assertTrue(selected.await(5, TimeUnit.SECONDS), "purge never selected the old row");
        Claim.Won replacement = won(b);
        if (completed) {
          b.complete(KEY, replacement.token(), response("new"), TTL);
        }
      } finally {
        resume.countDown();
      }
      assertEquals(0, task.get(5, TimeUnit.SECONDS), "a stale candidate is not a deletion");
    }
    assertEquals(1, rowCount());
    if (completed) {
      assertReplay("new");
    } else {
      assertInstanceOf(Claim.InProgress.class, b.claim(KEY, "fingerprint", LEASE));
    }
  }

  @Test
  void purgeKeepsAnExpiredPendingClaim() {
    won(a);
    clock.advance(LEASE.plusSeconds(1));
    assertEquals(0, b.deleteExpired(100));
    assertEquals(1, rowCount());
  }

  @Test
  void anUnknownOutcomeRemainsBlockedBeyondTheResponseTtl() {
    won(a);
    clock.advance(LEASE);
    assertInstanceOf(Claim.OutcomeUnknown.class, b.claim(KEY, "fingerprint", LEASE));
    clock.advance(TTL.plusDays(1));
    assertEquals(0, b.deleteExpired(100));
    assertInstanceOf(Claim.OutcomeUnknown.class, b.claim(KEY, "fingerprint", LEASE));
    assertInstanceOf(Claim.Mismatch.class, b.claim(KEY, "different request", LEASE));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void legacyRowsWithoutATokenStillBlockOrReplay(boolean completed) {
    Claim.Won original = won(a);
    if (completed) {
      a.complete(KEY, original.token(), response("legacy response"), TTL);
    }
    jdbc.update("UPDATE persimmon_idempotency SET claim_token = NULL");
    clock.advance(LEASE);
    if (completed) {
      assertReplay("legacy response");
      clock.advance(TTL);
      assertEquals(1, b.deleteExpired(100));
    } else {
      assertInstanceOf(Claim.OutcomeUnknown.class, b.claim(KEY, "fingerprint", LEASE));
      assertEquals(0, b.deleteExpired(100));
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aPreviousOwnerCannotCompleteAReplacement(boolean completed) {
    Claim.Won old = won(a);
    a.abandon(KEY, old.token());
    Claim.Won replacement = won(b);
    if (completed) {
      b.complete(KEY, replacement.token(), response("new"), TTL);
    }
    assertThrows(IllegalStateException.class, () -> a.complete(KEY, old.token(), response("stale"), TTL));
    if (completed) {
      assertReplay("new");
    } else {
      assertInstanceOf(Claim.InProgress.class, b.claim(KEY, "fingerprint", LEASE));
    }
  }

  @Test
  void aPreviousOwnerCannotAbandonAReplacement() {
    Claim.Won old = won(a);
    a.abandon(KEY, old.token());
    won(b);
    a.abandon(KEY, old.token());
    assertInstanceOf(Claim.InProgress.class, b.claim(KEY, "fingerprint", LEASE));
  }

  @Test
  void completionDoesNotSilentlySucceedAfterTheRowDisappears() {
    Claim.Won old = won(a);
    jdbc.update("DELETE FROM persimmon_idempotency");
    assertThrows(IllegalStateException.class, () -> a.complete(KEY, old.token(), response("lost"), TTL));
  }

  @Test
  void leaseExpiryDoesNotExecuteAgainAndTheOriginalRequestCanStillComplete() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    MockHttpServletResponse original = new MockHttpServletResponse();
    filter(a).doFilter(request(), original, (request, response) -> {
      executions.incrementAndGet();
      clock.advance(LEASE.plusSeconds(1));
      MockHttpServletResponse retry = new MockHttpServletResponse();
      filter(b).doFilter(request(), retry, (r, s) -> executions.incrementAndGet());
      assertEquals(409, retry.getStatus());
      assertEquals("idempotency.result-unknown", json.readTree(retry.getContentAsByteArray()).path("code").asText());
      assertNull(retry.getHeader("Retry-After"));
      response.getWriter().write("original result");
    });
    MockHttpServletResponse replay = new MockHttpServletResponse();
    filter(b).doFilter(request(), replay, (r, s) -> executions.incrementAndGet());
    assertEquals(1, executions.get());
    assertEquals("original result", replay.getContentAsString());
    assertEquals("true", replay.getHeader("Idempotent-Replayed"));
  }

  @Test
  void aFailedResponseWriteDoesNotReleaseTheClaimOrSendTheBufferedSuccess() throws Exception {
    MybatisIdempotencyStore failing = spy(a);
    doThrow(new IllegalStateException("database unavailable")).when(failing).complete(any(), any(), any(), any());
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertThrows(IllegalStateException.class, () -> filter(failing).doFilter(request(), response, (r, s) -> s.getWriter().write("success")));
    assertEquals("", response.getContentAsString());
    MockHttpServletResponse retry = new MockHttpServletResponse();
    AtomicInteger executions = new AtomicInteger();
    filter(b).doFilter(request(), retry, (r, s) -> executions.incrementAndGet());
    assertEquals(409, retry.getStatus());
    assertEquals(0, executions.get());
    clock.advance(LEASE.plusSeconds(1));
    assertEquals(0, b.deleteExpired(100));
    MockHttpServletResponse lateRetry = new MockHttpServletResponse();
    filter(b).doFilter(request(), lateRetry, (r, s) -> executions.incrementAndGet());
    assertEquals(409, lateRetry.getStatus());
    assertEquals(0, executions.get());
  }

  @Test
  void anUnhandledFailureAfterASideEffectDoesNotReleaseTheClaim() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    assertThrows(IllegalStateException.class, () -> filter(a).doFilter(request(), new MockHttpServletResponse(), (r, s) -> {
      executions.incrementAndGet();
      throw new IllegalStateException("failed after side effect");
    }));
    MockHttpServletResponse retry = new MockHttpServletResponse();
    filter(b).doFilter(request(), retry, (r, s) -> executions.incrementAndGet());
    assertEquals(409, retry.getStatus());
    assertEquals(1, executions.get());
  }

  private Claim.Won won(MybatisIdempotencyStore store) {
    return assertInstanceOf(Claim.Won.class, store.claim(KEY, "fingerprint", LEASE));
  }

  private void assertReplay(String body) {
    Claim.Replay replay = assertInstanceOf(Claim.Replay.class, b.claim(KEY, "fingerprint", LEASE));
    assertEquals(body, new String(replay.response().body(), StandardCharsets.UTF_8));
  }

  private int rowCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM persimmon_idempotency", Integer.class);
  }

  private StoredResponse response(String body) {
    return new StoredResponse(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
  }

  private MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/payments");
    request.addHeader("Idempotency-Key", KEY.value());
    request.setContentType("application/json");
    request.setContent("{}".getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private IdempotencyFilter filter(IdempotencyStore store) {
    return new IdempotencyFilter(store,
        new IdempotencyProperties(true, "Idempotency-Key", TTL, LEASE, false, List.of("POST"), DataSize.ofMegabytes(1), List.of()),
        json, ResponseRetention.DEFAULT, List.of());
  }

  static final class TestClock extends Clock {
    private volatile Instant now = Instant.parse("2026-10-31T00:00:00Z");

    void advance(Duration duration) { now = now.plus(duration); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
