package com.persimmon.ddd.outbox.inventory;

import com.persimmon.ddd.application.EventEnvelope;
import com.persimmon.ddd.outbox.OutboxTest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.event.EventListener;

/** Lives in context "inventory"; fails on demand to show the other context is not dragged along. */
public class InventoryListener {
  public final List<String> reserved = new ArrayList<>();
  public RuntimeException failWith;

  @EventListener
  void on(EventEnvelope<OutboxTest.OrderPlaced> envelope) {
    if (failWith != null) {
      throw failWith;
    }
    reserved.add(envelope.payload().orderId());
  }
}
