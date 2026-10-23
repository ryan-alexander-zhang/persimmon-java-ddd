package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.CommandContext;
import com.persimmon.ddd.application.CommandHandler;
import com.persimmon.ddd.application.CommandInterceptor;
import com.persimmon.ddd.application.SimpleCommandBus;
import com.persimmon.ddd.core.IdGenerator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * A {@link SimpleCommandBus} filled from the context's handler and interceptor beans once all
 * singletons exist, so a handler may itself inject the bus.
 */
public class SpringCommandBus implements CommandBus, SmartInitializingSingleton {
  private final ConfigurableListableBeanFactory beanFactory;
  private final ObjectProvider<CommandInterceptor> interceptors;
  private final IdGenerator ids;
  private volatile SimpleCommandBus delegate;

  public SpringCommandBus(
      ConfigurableListableBeanFactory beanFactory,
      ObjectProvider<CommandInterceptor> interceptors,
      IdGenerator ids) {
    this.beanFactory = beanFactory;
    this.interceptors = interceptors;
    this.ids = ids;
  }

  @Override
  public void afterSingletonsInstantiated() {
    delegate();
  }

  @Override
  public <R> R send(Command<R> command) {
    return delegate().send(command);
  }

  @Override
  public <R> R send(Command<R> command, CommandContext cause) {
    return delegate().send(command, cause);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private SimpleCommandBus delegate() {
    SimpleCommandBus bus = delegate;
    if (bus == null) {
      synchronized (this) {
        bus = delegate;
        if (bus == null) {
          SimpleCommandBus built = new SimpleCommandBus(ids, interceptors.orderedStream().toList());
          Handlers.forEach(beanFactory, CommandHandler.class, built::register);
          bus = built;
          delegate = bus;
        }
      }
    }
    return bus;
  }
}
