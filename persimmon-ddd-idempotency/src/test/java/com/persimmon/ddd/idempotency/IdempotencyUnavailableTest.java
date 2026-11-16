package com.persimmon.ddd.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.persimmon.ddd.web.ProblemCustomizer;
import java.sql.SQLTransientConnectionException;
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
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** A claim the store cannot take because it is unreachable is 503, before the handler runs. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:idem-unavailable;DB_CLOSE_DELAY=-1",
    "persimmon.ddd.web.unavailable.code=context.store-unavailable",
    "persimmon.ddd.web.unavailable.retry-after=2s"})
@AutoConfigureMockMvc
class IdempotencyUnavailableTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    final AtomicInteger sent = new AtomicInteger();

    @PostMapping("/messages")
    Map<String, Object> send() {
      return Map.of("sent", sent.incrementAndGet());
    }

    @Bean
    IdempotencyStore store() {
      return new IdempotencyStore() {
        @Override
        public Claim claim(Key key, String fingerprint, Duration lease, String token) {
          if (key.value().equals("down")) {
            throw new RuntimeException("mapper failed", new CannotGetJdbcConnectionException("password=secret",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out")));
          }
          throw new IllegalStateException("not a connection problem");
        }

        @Override
        public void complete(Key key, String token, StoredResponse response, Duration ttl) {}

        @Override
        public void abandon(Key key, String token) {}

        @Override
        public int deleteExpired(int limit) {
          return 0;
        }
      };
    }

    @Bean
    ProblemCustomizer gateway() {
      return (problem, headers) -> {
        problem.setProperty("sent", false);
        headers.set("X-Gateway-Source", "gateway");
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired App app;

  @BeforeEach
  void clean() {
    app.sent.set(0);
  }

  @Test
  void anUnreachableStoreAnswers503WithTheFrameworksCodeAndTheHandlerNeverRuns() throws Exception {
    mvc.perform(post("/messages").header("Idempotency-Key", "down"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "2"))
        .andExpect(header().string("X-Gateway-Source", "gateway"))
        .andExpect(jsonPath("$.type").value("/problems/service-unavailable"))
        .andExpect(jsonPath("$.code").value("idempotency.unavailable"))
        .andExpect(jsonPath("$.detail").value("A dependency this service needs is unavailable; retry later."))
        .andExpect(jsonPath("$.sent").value(false));
    assertEquals(0, app.sent.get());
  }

  @Test
  void anyOtherClaimFailureStillEscapes() {
    Exception thrown = assertThrows(Exception.class, () -> mvc.perform(post("/messages").header("Idempotency-Key", "broken")));
    assertInstanceOf(IllegalStateException.class, thrown.getCause() == null ? thrown : thrown.getCause());
    assertEquals(0, app.sent.get());
  }
}
