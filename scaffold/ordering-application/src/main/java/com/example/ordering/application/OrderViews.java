package com.example.ordering.application;

import java.util.Optional;

/** Read side port; implemented in infrastructure straight over the tables. */
public interface OrderViews {
  Optional<OrderView> find(String id);
}
