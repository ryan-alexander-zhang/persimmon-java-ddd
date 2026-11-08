package com.persimmon.ddd.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** A request header named in {@code fingerprint-headers} is part of "the same request". */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:idem-fingerprint;DB_CLOSE_DELAY=-1",
    "persimmon.ddd.idempotency.fingerprint-headers=X-Gateway-Creator"})
@AutoConfigureMockMvc
class IdempotencyFingerprintHeadersTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    final AtomicInteger sent = new AtomicInteger();

    @PostMapping("/chats/456/messages")
    Map<String, Object> send() {
      return Map.of("sent", sent.incrementAndGet());
    }
  }

  @Autowired MockMvc mvc;
  @Autowired App app;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM persimmon_idempotency");
    app.sent.set(0);
  }

  private static MockHttpServletRequestBuilder send(String key, String creator) {
    var request = post("/chats/456/messages").header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hi\"}");
    return creator == null ? request : request.header("X-Gateway-Creator", creator);
  }

  @Test
  void theSameKeyForAnotherValueOfADeclaredHeaderIsRejectedNotReplayed() throws Exception {
    mvc.perform(send("mass-send:plan-123:fan-456", "480")).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(1));
    mvc.perform(send("mass-send:plan-123:fan-456", "480"))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.sent").value(1));
    mvc.perform(send("mass-send:plan-123:fan-456", "481"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("idempotency.key-reused"));
    assertEquals(1, app.sent.get());
  }

  @Test
  void anAbsentHeaderAndAnEmptyOneAreDifferentRequests() throws Exception {
    mvc.perform(send("k-absent", null)).andExpect(status().isOk());
    mvc.perform(send("k-absent", null)).andExpect(header().string("Idempotent-Replayed", "true"));
    mvc.perform(send("k-absent", "")).andExpect(status().isUnprocessableEntity());
    assertEquals(1, app.sent.get());
  }
}
