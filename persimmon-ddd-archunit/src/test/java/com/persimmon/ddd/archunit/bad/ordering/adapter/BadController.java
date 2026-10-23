package com.persimmon.ddd.archunit.bad.ordering.adapter;

import com.persimmon.ddd.archunit.bad.ordering.domain.Customer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BadController {
  @GetMapping("/c")
  Customer get() {
    return null;
  }
}
