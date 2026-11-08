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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Request headers named in {@code key-scope-headers} make the same key another key. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:idem-scope;DB_CLOSE_DELAY=-1",
    "persimmon.ddd.idempotency.key-scope-headers=X-Gateway-Creator,x-gateway-caller"})
@AutoConfigureMockMvc
class IdempotencyKeyScopeHeadersTest {
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    final AtomicInteger sent = new AtomicInteger();

    @PostMapping("/chats/456/messages")
    Map<String, Object> send(@RequestBody Map<String, Object> body) {
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

  private static MockHttpServletRequestBuilder send(String key, String caller, String creator, String text) {
    var request = post("/chats/456/messages").header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + text + "\"}");
    if (caller != null) {
      request.header("X-Gateway-Caller", caller);
    }
    return creator == null ? request : request.header("X-Gateway-Creator", creator);
  }

  @Test
  void theSameKeyForAnotherScopeValueRunsOnItsOwn() throws Exception {
    mvc.perform(send("k", "mass-send", "480", "hi")).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(1));
    mvc.perform(send("k", "mass-send", "481", "hi")).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(2));
    mvc.perform(send("k", "reply-bot", "480", "hi")).andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(3));
    mvc.perform(send("k", "mass-send", "480", "hi"))
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.sent").value(1));
    mvc.perform(send("k", "mass-send", "481", "bye"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("idempotency.key-reused"));
    assertEquals(3, app.sent.get());
  }

  @Test
  void anAbsentHeaderAndAnEmptyOneAreDifferentScopes() throws Exception {
    mvc.perform(send("k", "mass-send", null, "hi")).andExpect(jsonPath("$.sent").value(1));
    mvc.perform(send("k", "mass-send", "", "hi")).andExpect(jsonPath("$.sent").value(2));
    mvc.perform(send("k", "mass-send", null, "hi")).andExpect(header().string("Idempotent-Replayed", "true"));
    assertEquals(2, app.sent.get());
  }

  @Test
  void theKeyKeepsAll255CharactersAndTheScopeFitsThePrincipalColumn() throws Exception {
    String key = "k".repeat(255);
    mvc.perform(send(key, "c".repeat(1000), "480", "hi")).andExpect(status().isOk());
    assertEquals(64, jdbc.queryForObject("SELECT LENGTH(principal) FROM persimmon_idempotency", Integer.class));
    assertEquals(255, jdbc.queryForObject("SELECT LENGTH(idempotency_key) FROM persimmon_idempotency", Integer.class));
  }
}
