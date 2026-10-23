package com.example.app;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.app.ordering.api.OrderConfirmedEvent;
import com.example.app.ordering.api.OrderLookup;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.outbox.OutboxRelay;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {"persimmon.ddd.outbox.relay-enabled=false", "ordering.confirmation-timeout=0s"})
@AutoConfigureMockMvc
class OrderingFlowTest {
  @TestConfiguration
  static class Probe {
    final List<EventEnvelope<OrderConfirmedEvent>> confirmed = new CopyOnWriteArrayList<>();

    /** Every request names a tenant: harmless while tenancy is off, required once it is on. */
    @Bean
    MockMvcBuilderCustomizer tenantHeader() {
      return builder -> builder.defaultRequest(get("/").header("X-Tenant-Id", "acme"));
    }

    @Bean
    Object confirmedProbe() {
      return new Object() {
        @EventListener
        void on(EventEnvelope<OrderConfirmedEvent> envelope) {
          confirmed.add(envelope);
        }
      };
    }
  }

  @Autowired MockMvc mvc;
  @Autowired OutboxRelay relay;
  @Autowired Probe probe;
  @Autowired OrderLookup lookup;

  private static final String ORDER =
      "{\"customerId\":\"c-1\",\"lines\":[{\"sku\":\"A\",\"quantity\":2},{\"sku\":\"B\",\"quantity\":1}]}";

  @Test
  void placeConfirmAndReadAnOrder() throws Exception {
    String id =
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    mvc.perform(get("/orders/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PLACED"))
        .andExpect(jsonPath("$.lines", hasSize(2)));

    mvc.perform(post("/orders/" + id + "/confirm").header("Idempotency-Key", "confirm-1")).andExpect(status().isNoContent());
    mvc.perform(post("/orders/" + id + "/confirm").header("Idempotency-Key", "confirm-1"))
        .andExpect(status().isNoContent());
    mvc.perform(post("/orders/" + id + "/confirm"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ordering.order-not-confirmable"));

    relay.relay();
    assertEquals(id, probe.confirmed.get(0).payload().orderId());
    assertEquals("CONFIRMED", lookup.find(id).orElseThrow().status(), "the deadline found it confirmed and let it be");
  }

  @Test
  void anOrderNotConfirmedInTimeIsCancelledByTheDeadline() throws Exception {
    String id =
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER))
            .andReturn().getResponse().getContentAsString()
            .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    relay.relay();
    mvc.perform(get("/orders/" + id)).andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void badInputAndUnknownOrdersAreProblemDetails() throws Exception {
    mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\"c-1\",\"lines\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("lines"));
    mvc.perform(get("/orders/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ordering.order-not-found"));
  }
}
