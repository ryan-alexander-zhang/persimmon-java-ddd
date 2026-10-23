package com.example.app.ordering.domain;

import com.persimmon.ddd.core.Identifier;
import com.persimmon.ddd.core.annotation.ValueObject;

@ValueObject
public record OrderId(String value) implements Identifier {}
