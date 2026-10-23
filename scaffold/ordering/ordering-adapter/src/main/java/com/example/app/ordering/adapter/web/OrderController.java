package com.example.app.ordering.adapter.web;

import com.example.app.ordering.application.ConfirmOrder;
import com.example.app.ordering.application.FindOrder;
import com.example.app.ordering.application.OrderView;
import com.example.app.ordering.application.PlaceOrder;
import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.QueryBus;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin: translate HTTP to commands and queries; errors become problem details on their own. */
@RestController
@RequestMapping("/orders")
class OrderController {
  private final CommandBus commands;
  private final QueryBus queries;

  OrderController(CommandBus commands, QueryBus queries) {
    this.commands = commands;
    this.queries = queries;
  }

  @PostMapping
  ResponseEntity<Map<String, String>> place(@RequestBody PlaceOrder command) {
    String id = commands.send(command);
    return ResponseEntity.created(URI.create("/orders/" + id)).body(Map.of("id", id));
  }

  @PostMapping("/{id}/confirm")
  ResponseEntity<Void> confirm(@PathVariable String id) {
    commands.send(new ConfirmOrder(id));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}")
  OrderView get(@PathVariable String id) {
    return queries.ask(new FindOrder(id));
  }
}
