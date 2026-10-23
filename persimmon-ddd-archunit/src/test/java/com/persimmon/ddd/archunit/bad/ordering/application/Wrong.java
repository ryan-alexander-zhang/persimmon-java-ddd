package com.persimmon.ddd.archunit.bad.ordering.application;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.Query;
import com.persimmon.ddd.archunit.bad.ordering.domain.Customer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;

public class Wrong {
  public record Untyped(String x) implements IntegrationEvent {}

  public record Fetch(String id) implements Query<Customer.Order> {}

  public static class A implements CommandHandler<Cmd, String> {
    @Autowired B other;

    @Override
    public String handle(Cmd command, CommandContext context) {
      return "";
    }
  }

  public static class B implements CommandHandler<Cmd, String> {
    @Override
    public String handle(Cmd command, CommandContext context) {
      return "";
    }
  }

  public record Cmd() implements Command<String> {}

  public static class Listener {
    @EventListener
    void on(Customer.Id notAnEventButInDomain) {}
  }
}
