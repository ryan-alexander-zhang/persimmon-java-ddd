package com.example.app.ordering.application;

import com.persimmon.ddd.application.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/** Returns the new order's id. */
public record PlaceOrder(@NotBlank String customerId, @NotEmpty List<@Valid Line> lines)
    implements Command<String> {
  public record Line(@NotBlank String sku, @Positive int quantity) {}
}
