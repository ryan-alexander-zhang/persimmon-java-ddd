package com.persimmon.ddd.outbox.billing;

import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.outbox.OutboxTest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.event.EventListener;

/** Lives in context "billing" (first package under the test's base package). */
public class BillingListener {
  public final List<String> invoiced = new ArrayList<>();

  @EventListener
  void on(EventEnvelope<OutboxTest.OrderPlaced> envelope) {
    invoiced.add(envelope.payload().orderId());
  }
}
