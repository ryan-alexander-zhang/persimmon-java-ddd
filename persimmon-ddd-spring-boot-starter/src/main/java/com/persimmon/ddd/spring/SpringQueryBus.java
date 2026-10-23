package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.Query;
import com.persimmon.ddd.application.QueryBus;
import com.persimmon.ddd.application.QueryHandler;
import com.persimmon.ddd.application.SimpleQueryBus;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/** A {@link SimpleQueryBus} filled from the context's handler beans once all singletons exist. */
public class SpringQueryBus implements QueryBus, SmartInitializingSingleton {
  private final ConfigurableListableBeanFactory beanFactory;
  private volatile SimpleQueryBus delegate;

  public SpringQueryBus(ConfigurableListableBeanFactory beanFactory) {
    this.beanFactory = beanFactory;
  }

  @Override
  public void afterSingletonsInstantiated() {
    delegate();
  }

  @Override
  public <R> R ask(Query<R> query) {
    return delegate().ask(query);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private SimpleQueryBus delegate() {
    SimpleQueryBus bus = delegate;
    if (bus == null) {
      synchronized (this) {
        bus = delegate;
        if (bus == null) {
          SimpleQueryBus built = new SimpleQueryBus();
          Handlers.forEach(beanFactory, QueryHandler.class, built::register);
          bus = built;
          delegate = bus;
        }
      }
    }
    return bus;
  }
}
