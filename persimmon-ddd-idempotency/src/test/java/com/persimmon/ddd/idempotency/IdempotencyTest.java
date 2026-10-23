package com.persimmon.ddd.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    final AtomicInteger payments = new AtomicInteger();

    @PostMapping("/payments")
    ResponseEntity<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
      if ("boom".equals(body.get("amount"))) {
        throw new IllegalStateException("gateway down");
      }
      int n = payments.incrementAndGet();
      return ResponseEntity.created(URI.create("/payments/" + n)).body(Map.of("id", n, "amount", body.get("amount")));
    }
  }

  @Autowired MockMvc mvc;
  @Autowired App app;
  @Autowired JdbcTemplate jdbc;
  @Autowired IdempotencyStore store;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM persimmon_idempotency");
    app.payments.set(0);
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pay(String key, String body) {
    var request = post("/payments").contentType(MediaType.APPLICATION_JSON).content(body);
    return key == null ? request : request.header("Idempotency-Key", key);
  }

  @Test
  void aRetryWithTheSameKeyReplaysTheFirstResponseWithoutRunningAgain() throws Exception {
    mvc.perform(pay("k-1", "{\"amount\":10}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/payments/1"))
        .andExpect(jsonPath("$.id").value(1));
    mvc.perform(pay("k-1", "{\"amount\":10}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/payments/1"))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.id").value(1));
    assertEquals(1, app.payments.get());
    mvc.perform(pay(null, "{\"amount\":10}")).andExpect(jsonPath("$.id").value(2));
  }

  @Test
  void theSameKeyWithADifferentBodyIsRejected() throws Exception {
    mvc.perform(pay("k-1", "{\"amount\":10}")).andExpect(status().isCreated());
    mvc.perform(pay("k-1", "{\"amount\":99}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("/problems/idempotency-key-reused"));
    assertEquals(1, app.payments.get());
  }

  @Test
  void aKeyStillInFlightAnswersConflictAndAFailedRequestCanBeRetried() throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update("POST\n/payments\nnull\napplication/json\n{\"amount\":10}".getBytes(StandardCharsets.UTF_8));
    store.claim(new IdempotencyStore.Key("__root__", "", "k-busy"), HexFormat.of().formatHex(digest.digest()), Duration.ofMinutes(1));
    mvc.perform(pay("k-busy", "{\"amount\":10}"))
        .andExpect(status().isConflict())
        .andExpect(header().string("Retry-After", "1"));

    mvc.perform(pay("k-fail", "{\"amount\":\"boom\"}")).andExpect(status().isInternalServerError());
    mvc.perform(pay("k-fail", "{\"amount\":\"boom\"}")).andExpect(status().isInternalServerError());
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM persimmon_idempotency WHERE idempotency_key = 'k-fail'", Integer.class), "a failure leaves no claim behind");
  }

  @Test
  void expiredRowsArePurged() {
    store.claim(new IdempotencyStore.Key("__root__", "", "old"), "x", Duration.ofMinutes(-1));
    assertEquals(1, store.deleteExpired(100));
    assertEquals(0, store.deleteExpired(100));
  }
}
