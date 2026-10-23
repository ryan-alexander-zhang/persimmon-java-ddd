package com.example.app.ordering.application;

import com.persimmon.ddd.application.Command;
import jakarta.validation.constraints.NotBlank;

/** The deadline half of the confirmation saga; scheduled when the order is placed. */
public record CancelUnconfirmedOrder(@NotBlank String orderId) implements Command<Void> {}
