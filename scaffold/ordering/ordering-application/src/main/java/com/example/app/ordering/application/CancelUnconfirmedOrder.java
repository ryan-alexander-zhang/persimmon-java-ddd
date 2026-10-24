package com.example.app.ordering.application;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandType;
import jakarta.validation.constraints.NotBlank;

/** The deadline half of the confirmation saga; scheduled when the order is placed. The wire name outlives a rename. */
@CommandType("ordering.CancelUnconfirmedOrder")
public record CancelUnconfirmedOrder(@NotBlank String orderId) implements Command<Void> {}
