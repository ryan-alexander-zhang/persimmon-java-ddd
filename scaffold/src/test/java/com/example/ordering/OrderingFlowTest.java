package com.example.ordering;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.ordering.api.OrderConfirmedEvent;
import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.outbox.OutboxRelay;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "persimmon.ddd.outbox.relay-enabled=false")
@AutoConfigureMockMvc
class OrderingFlowTest {
  @TestConfiguration
  static class Probe {
    final List<EventEnvelope<OrderConfirmedEvent>> confirmed = new CopyOnWriteArrayList<>();

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

    mvc.perform(post("/orders/" + id + "/confirm")).andExpect(status().isNoContent());
    mvc.perform(post("/orders/" + id + "/confirm"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ordering.order-not-confirmable"));

    relay.relay();
    assertEquals(id, probe.confirmed.get(0).payload().orderId());
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
