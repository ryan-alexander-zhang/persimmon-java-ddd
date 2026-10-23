package com.persimmon.ddd.spring;

import java.lang.reflect.Modifier;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;

/**
 * Finds every bean of a handler interface and the concrete message class it declares as its
 * first type argument, from the bean definition first (a {@code @Bean} method's return type keeps
 * generics a lambda loses) and the instance otherwise.
 */
final class Handlers {
  private Handlers() {}

  @SuppressWarnings({"unchecked", "rawtypes"})
  static <H> void forEach(
      ConfigurableListableBeanFactory beanFactory,
      Class<H> handlerInterface,
      BiConsumer<Class, H> register) {
    for (String name : beanFactory.getBeanNamesForType(handlerInterface)) {
      H handler = beanFactory.getBean(name, handlerInterface);
      Class<?> type =
          beanFactory
              .getMergedBeanDefinition(name)
              .getResolvableType()
              .as(handlerInterface)
              .getGeneric(0)
              .resolve();
      if (type == null) {
        type = ResolvableType.forInstance(handler).as(handlerInterface).getGeneric(0).resolve();
      }
      if (type == null || type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
        throw new IllegalStateException(
            "bean '"
                + name
                + "' must declare a concrete type for its "
                + handlerInterface.getSimpleName()
                + " type parameter; handlers are matched by the message's exact class");
      }
      register.accept(type, handler);
    }
  }
}
