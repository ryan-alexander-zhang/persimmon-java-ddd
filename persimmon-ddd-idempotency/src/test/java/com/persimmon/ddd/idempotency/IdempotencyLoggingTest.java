package com.persimmon.ddd.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.idempotency.IdempotencyStore.Claim;
import com.persimmon.ddd.idempotency.IdempotencyStore.Key;
import com.persimmon.ddd.idempotency.IdempotencyStore.StoredResponse;
import com.persimmon.ddd.web.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

@SpringBootTest(classes = IdempotencyTest.App.class,
    properties = "spring.datasource.url=jdbc:h2:mem:idem-logging;DB_CLOSE_DELAY=-1")
class IdempotencyLoggingTest {
  private static final Duration TTL = Duration.ofHours(24);
  private static final Duration LEASE = Duration.ofSeconds(150);
  private static final Key KEY = new Key("__root__", "", "key-secret");
  @Autowired ObjectMapper json;
  @Autowired IdempotencyMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private MybatisIdempotencyStore store;
  private final Logger log = (Logger) LoggerFactory.getLogger("com.persimmon.ddd.idempotency");
  private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
    @Override protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };
  private Level previousLevel;
  private boolean previousAdditive;

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM persimmon_idempotency");
    store = new MybatisIdempotencyStore(mapper, json, Clock.systemUTC());
    MDC.clear();
    MDC.put("requestId", "req-42");
    MDC.put("traceId", "trace-42");
    MDC.put("spanId", "span-42");
    previousLevel = log.getLevel();
    previousAdditive = log.isAdditive();
    log.setLevel(Level.INFO);
    log.setAdditive(false);
    events.start();
    log.addAppender(events);
  }

  @AfterEach
  void tearDown() {
    log.detachAppender(events);
    events.stop();
    log.setLevel(previousLevel);
    log.setAdditive(previousAdditive);
    MDC.clear();
    for (ILoggingEvent event : events.list) {
      assertNull(event.getThrowableProxy(), "exception messages and causes may contain secrets");
      assertFalse(event.getFormattedMessage().contains("secret"));
      assertEquals("req-42", event.getMDCPropertyMap().get("requestId"));
    }
  }

  @ParameterizedTest
  @MethodSource("refusals")
  void refusalsExplainWhyBusinessDidNotRun(Claim claim, int status, String code, String retryAfter) throws Exception {
    IdempotencyStore target = mock(IdempotencyStore.class);
    when(target.claim(any(), any(), any())).thenReturn(claim);
    MockHttpServletResponse response = new MockHttpServletResponse();
    invoke(target, ResponseRetention.DEFAULT, request(), response, (r, s) -> fail("must not execute"));
    assertEquals(status, response.getStatus());
    assertEquals(code, json.readTree(response.getContentAsString()).get("code").asText());
    assertEquals(retryAfter, response.getHeader("Retry-After"));
    assertMessages("event=idempotency_decision outcome=rejected code=" + code + " response_status=" + status);
    verify(target, never()).complete(any(), any(), any(), any());
    verify(target, never()).abandon(any(), any());
    assertCorrelation();
  }

  static Stream<Arguments> refusals() {
    return Stream.of(
        Arguments.of(new Claim.InProgress(), 409, "idempotency.in-progress", "1"),
        Arguments.of(new Claim.OutcomeUnknown(), 409, "idempotency.result-unknown", null),
        Arguments.of(new Claim.Mismatch(), 422, "idempotency.key-reused", null));
  }

  @ParameterizedTest
  @CsvSource({"missing,400,idempotency.key-required", "blank,400,idempotency.key-required",
      "long,400,idempotency.key-too-long", "large,413,idempotency.request-too-large"})
  void invalidInputIsExplainedBeforeClaim(String input, int status, String code) throws Exception {
    MockHttpServletRequest request = request();
    if (input.equals("missing")) request.removeHeader("Idempotency-Key");
    if (input.equals("blank")) {
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", " ");
    }
    if (input.equals("long")) {
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", "key-secret".repeat(30));
    }
    if (input.equals("large")) request.setContent(new byte[1025]);
    IdempotencyStore target = mock(IdempotencyStore.class);
    MockHttpServletResponse response = new MockHttpServletResponse();
    invoke(target, ResponseRetention.DEFAULT, request, response, (r, s) -> fail("must not execute"));
    assertEquals(status, response.getStatus());
    assertEquals(code, json.readTree(response.getContentAsString()).get("code").asText());
    assertMessages("event=idempotency_decision outcome=rejected code=" + code + " response_status=" + status);
    verifyNoInteractions(target);
  }

  @Test
  void replayIsIdentifiedWithoutExecutingOrChangingTheStoredResponse() throws Exception {
    IdempotencyStore target = mock(IdempotencyStore.class);
    when(target.claim(any(), any(), any())).thenReturn(new Claim.Replay(stored()));
    MockHttpServletResponse response = new MockHttpServletResponse();
    invoke(target, ResponseRetention.DEFAULT, request(), response, (r, s) -> fail("must not execute"));
    assertEquals(202, response.getStatus());
    assertEquals("body-secret", response.getContentAsString());
    assertEquals("true", response.getHeader("Idempotent-Replayed"));
    assertEquals("header-secret", response.getHeader("X-Result"));
    assertMessages("event=idempotency_decision outcome=replay response_status=202");
  }

  @Test
  void claimFailureIsCorrelatedAndTheSameExceptionEscapes() {
    IdempotencyStore target = mock(IdempotencyStore.class);
    RuntimeException failure = new IllegalStateException();
    when(target.claim(any(), any(), any())).thenThrow(failure);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(target,
        ResponseRetention.DEFAULT, request(), new MockHttpServletResponse(), (r, s) -> fail("must not execute"))));
    assertMessages("event=idempotency_decision outcome=error stage=claim error_type=java.lang.IllegalStateException");
    assertEquals(Level.ERROR, events.list.getFirst().getLevel());
    assertCorrelation();
  }

  @Test
  void completionIsLoggedOnlyAfterTheRecordIsSavedAndBeforeResponseCopy() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    invoke(store, ResponseRetention.DEFAULT, request(), response, (r, s) -> {
      assertTrue(events.list.isEmpty(), "no generic entry or admission-success log");
      s.getWriter().write("body-secret");
    });
    assertEquals("COMPLETE", state());
    assertEquals("body-secret", response.getContentAsString());
    assertMessages("event=idempotency_record action=complete outcome=saved response_status=200");
    assertCorrelation();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completionErrorDoesNotClaimTheDatabaseRolledBackOrSendSuccess(boolean persisted) throws Exception {
    MybatisIdempotencyStore target = spy(store);
    RuntimeException failure = new IllegalStateException("database-secret", new IOException("cause-secret"));
    doAnswer(call -> {
      if (persisted) call.callRealMethod();
      throw failure;
    }).when(target).complete(any(), any(), any(), any());
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(target,
        ResponseRetention.DEFAULT, request(), response, (r, s) -> s.getWriter().write("body-secret"))));
    assertEquals(persisted ? "COMPLETE" : "PENDING", state());
    assertEquals("", response.getContentAsString());
    assertMessages("event=idempotency_record action=complete outcome=error response_status=200 error_type=java.lang.IllegalStateException");
    verify(target, never()).abandon(any(), any());
    assertCorrelation();
  }

  @Test
  void failedOwnershipCheckIsDistinguishableFromAnUncertainDatabaseError() {
    assertThrows(IllegalStateException.class, () -> store.complete(KEY, "token-secret", stored(), TTL));
    assertMessages("event=idempotency_record action=complete outcome=not_owned affected_rows=0");
    assertEquals(Level.WARN, events.list.getFirst().getLevel());
  }

  @Test
  void releaseReportsActualDeletionAndKeepsTheResponse() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    invoke(store, ignored -> false, request(), response, (r, s) -> s.getWriter().write("body-secret"));
    assertEquals(0, rowCount());
    assertEquals("body-secret", response.getContentAsString());
    assertMessages("event=idempotency_record action=release outcome=released affected_rows=1");
  }

  @Test
  void releaseWithNoMatchingOwnershipDoesNotClaimSuccessOrDeleteTheOwner() {
    store.claim(KEY, "fingerprint-secret", LEASE);
    store.abandon(KEY, "wrong-token-secret");
    assertEquals("PENDING", state());
    assertMessages("event=idempotency_record action=release outcome=not_matched affected_rows=0");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void releaseFailureRemainsSwallowedWithoutClaimingRetriesAreDefinitelyBlocked(boolean deleted) throws Exception {
    IdempotencyMapper target = mock(IdempotencyMapper.class, delegatesTo(mapper));
    RuntimeException failure = new IllegalStateException("database-secret");
    doAnswer(call -> {
      // The first delete is claim's expired-COMPLETE cleanup; the second is release.
      if (rowCount() == 0) return mapper.delete(call.getArgument(0));
      if (deleted) mapper.delete(call.getArgument(0));
      throw failure;
    }).when(target).delete(any(Wrapper.class));
    MockHttpServletResponse response = new MockHttpServletResponse();
    invoke(new MybatisIdempotencyStore(target, json, Clock.systemUTC()), ignored -> false,
        request(), response, (r, s) -> s.getWriter().write("body-secret"));
    assertEquals(deleted ? 0 : 1, rowCount());
    assertEquals("body-secret", response.getContentAsString());
    assertMessages("event=idempotency_record action=release outcome=error response_status=200 error_type=java.lang.IllegalStateException");
    assertEquals(Level.WARN, events.list.getFirst().getLevel());
  }

  @Test
  void retentionFailureIsNotMisreportedAsAWriteOrDatabaseFailure() {
    RuntimeException failure = new IllegalStateException();
    assertSame(failure, assertThrows(IllegalStateException.class, () -> invoke(store,
        ignored -> { throw failure; }, request(), new MockHttpServletResponse(), (r, s) -> {})));
    assertEquals("PENDING", state());
    assertMessages("event=idempotency_record action=retention outcome=error response_status=200 error_type=java.lang.IllegalStateException");
  }

  @ParameterizedTest
  @CsvSource({"executed,false", "executed,true", "replay,false", "replay,true", "problem,false", "problem,true"})
  void writeFailuresHaveTheCorrectSourceAndDoNotChangePropagation(String source, boolean unchecked) throws Exception {
    Exception failure = unchecked ? new IllegalStateException() : new IOException("write-secret");
    MockHttpServletResponse underlying = new MockHttpServletResponse();
    HttpServletResponse response = new HttpServletResponseWrapper(underlying) {
      @Override public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
          @Override public boolean isReady() { return true; }
          @Override public void setWriteListener(WriteListener listener) {}
          @Override public void write(int value) throws IOException {
            if (failure instanceof IOException io) throw io;
            throw (RuntimeException) failure;
          }
        };
      }
    };
    IdempotencyStore target = store;
    if (!source.equals("executed")) {
      target = mock(IdempotencyStore.class);
      when(target.claim(any(), any(), any())).thenReturn(source.equals("replay")
          ? new Claim.Replay(stored()) : new Claim.InProgress());
    }
    IdempotencyStore chosen = target;
    Exception thrown = assertThrows(Exception.class, () -> invoke(chosen, ResponseRetention.DEFAULT,
        request(), response, (r, s) -> {
          assertEquals("executed", source, "short circuits must not run business");
          ((HttpServletResponse) s).setStatus(202);
          s.getWriter().write("body-secret");
        }));
    assertSame(failure, thrown);
    int status = source.equals("problem") ? 409 : 202;
    assertEquals(status, underlying.getStatus());
    String first = switch (source) {
      case "executed" -> "event=idempotency_record action=complete outcome=saved response_status=202";
      case "replay" -> "event=idempotency_decision outcome=replay response_status=202";
      default -> "event=idempotency_decision outcome=rejected code=idempotency.in-progress response_status=409";
    };
    assertMessages(first, "event=idempotency_response_write source=" + source
        + " outcome=error response_status=" + status + " error_type=" + failure.getClass().getName());
    assertEquals(Level.ERROR, events.list.getLast().getLevel());
    if (source.equals("executed")) assertEquals("COMPLETE", state());
    assertCorrelation();
  }

  @Test
  void flushFailureAfterCopyIsStillAWriteFailureWithoutAnExtraFlushOrRelease() {
    IOException failure = new IOException();
    MockHttpServletResponse underlying = new MockHttpServletResponse();
    AtomicInteger flushes = new AtomicInteger();
    HttpServletResponse response = new HttpServletResponseWrapper(underlying) {
      @Override public void flushBuffer() throws IOException {
        flushes.incrementAndGet();
        throw failure;
      }
    };
    assertSame(failure, assertThrows(IOException.class, () -> invoke(store, ResponseRetention.DEFAULT,
        request(), response, (r, s) -> s.getWriter().write("body-secret"))));
    assertEquals(1, flushes.get());
    assertEquals("COMPLETE", state());
    assertMessages("event=idempotency_record action=complete outcome=saved response_status=200",
        "event=idempotency_response_write source=executed outcome=error response_status=200 error_type=java.io.IOException");
    assertCorrelation();
  }

  @Test
  void optionalContextCanBeAbsentAndSuccessfulBypassDoesNotAddLogs() throws Exception {
    MDC.clear();
    MockHttpServletRequest request = request();
    request.setMethod("GET");
    IdempotencyStore target = mock(IdempotencyStore.class);
    invoke(target, ResponseRetention.DEFAULT, request, new MockHttpServletResponse(), (r, s) -> {});
    assertTrue(events.list.isEmpty());
    verifyNoInteractions(target);
    request.setMethod("POST");
    invoke(store, ResponseRetention.DEFAULT, request, new MockHttpServletResponse(), (r, s) -> {});
    assertMessages("event=idempotency_record action=complete outcome=saved response_status=200");
    assertEquals(Map.of("requestId", "req-42"), events.list.getFirst().getMDCPropertyMap());
  }

  private void invoke(IdempotencyStore target, ResponseRetention retention, MockHttpServletRequest request,
      HttpServletResponse response, FilterChain chain) throws Exception {
    IdempotencyFilter filter = new IdempotencyFilter(target,
        new IdempotencyProperties(true, "Idempotency-Key", TTL, LEASE, true,
            List.of("POST"), DataSize.ofBytes(1024), List.of()), json, retention, List.of());
    new RequestIdFilter("X-Request-Id").doFilter(request, response,
        (r, s) -> filter.doFilter(r, s, chain));
  }

  private MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/path-secret");
    request.addHeader("Idempotency-Key", KEY.value());
    request.addHeader("X-Request-Id", "req-42");
    request.addHeader("Authorization", "Bearer auth-secret");
    request.addHeader("Cookie", "cookie-secret");
    request.setQueryString("token=query-secret");
    request.setContent("request-secret".getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private StoredResponse stored() {
    return new StoredResponse(202, "body-secret".getBytes(StandardCharsets.UTF_8), Map.of("X-Result", "header-secret"));
  }

  private int rowCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM persimmon_idempotency", Integer.class); }
  private String state() { return jdbc.queryForObject("SELECT state FROM persimmon_idempotency", String.class); }

  private void assertMessages(String... expected) {
    assertEquals(List.of(expected), events.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
  }

  private void assertCorrelation() {
    assertNull(MDC.get("requestId"), "the outer request filter still cleans up");
    for (ILoggingEvent event : events.list) {
      assertEquals(Map.of("requestId", "req-42", "traceId", "trace-42", "spanId", "span-42"), event.getMDCPropertyMap());
    }
  }
}
