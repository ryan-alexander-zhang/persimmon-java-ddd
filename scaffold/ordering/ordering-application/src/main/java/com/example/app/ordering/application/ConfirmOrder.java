package com.example.app.ordering.application;

import com.persimmon.ddd.application.Command;
import jakarta.validation.constraints.NotBlank;

public record ConfirmOrder(@NotBlank String orderId) implements Command<Void> {}
