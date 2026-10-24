package com.persimmon.ddd.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code persimmon.ddd.command.*}. */
@ConfigurationProperties("persimmon.ddd.command")
public record CommandProperties(
    /* How many times a command that lost an optimistic lock is run again; 0 turns it off. */
    @DefaultValue("3") int conflictRetries) {}
