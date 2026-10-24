package com.persimmon.ddd.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.Command;
import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.CommandScheduler;
import com.persimmon.ddd.application.IntegrationEvent;
import com.persimmon.ddd.application.IntegrationEvents;
import com.persimmon.ddd.core.IdGenerator;
import com.persimmon.ddd.outbox.mybatis.DeadLetterMapper;
import com.persimmon.ddd.outbox.mybatis.InboxMapper;
import com.persimmon.ddd.outbox.mybatis.MybatisOutboxStore;
import com.persimmon.ddd.outbox.mybatis.OutboxMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.ResolvableType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

@AutoConfiguration(
    afterName = {
      "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
      "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
    })
@ConditionalOnBean(SqlSessionFactory.class)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
@Import(OutboxAutoConfiguration.Mappers.class)
public class OutboxAutoConfiguration {
  private static final Logger log = LoggerFactory.getLogger(OutboxAutoConfiguration.class);

  static class Mappers {
    @Bean
    MapperFactoryBean<OutboxMapper> persimmonOutboxMapper(SqlSessionFactory factory) {
      return mapper(OutboxMapper.class, factory);
    }

    @Bean
    MapperFactoryBean<DeadLetterMapper> persimmonDeadLetterMapper(SqlSessionFactory factory) {
      return mapper(DeadLetterMapper.class, factory);
    }

    @Bean
    MapperFactoryBean<InboxMapper> persimmonInboxMapper(SqlSessionFactory factory) {
      return mapper(InboxMapper.class, factory);
    }

    private static <M> MapperFactoryBean<M> mapper(Class<M> type, SqlSessionFactory factory) {
      MapperFactoryBean<M> bean = new MapperFactoryBean<>(type);
      bean.setSqlSessionFactory(factory);
      return bean;
    }
  }

  @Bean
  @ConditionalOnMissingBean(name = "persimmonOutboxClock")
  public Clock persimmonOutboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public OutboxStore persimmonOutboxStore(
      OutboxMapper outbox, DeadLetterMapper deadLetters, InboxMapper inbox) {
    return new MybatisOutboxStore(outbox, deadLetters, inbox);
  }

  @Bean
  @ConditionalOnMissingBean
  public IntegrationEvents persimmonOutboxWriter(
      OutboxStore store,
      ObjectProvider<ObjectMapper> json,
      IdGenerator ids,
      Clock persimmonOutboxClock,
      OutboxProperties properties,
      ObjectProvider<TraceContextCarrier> traces) {
    return new OutboxWriter(
        store, json.getIfAvailable(ObjectMapper::new), ids, persimmonOutboxClock, properties,
        traces.getIfAvailable(() -> TraceContextCarrier.NOOP));
  }

  @Bean
  @ConditionalOnMissingBean
  public CommandScheduler persimmonCommandScheduler(
      OutboxStore store,
      ObjectProvider<ObjectMapper> json,
      IdGenerator ids,
      Clock persimmonOutboxClock,
      ObjectProvider<TraceContextCarrier> traces) {
    return new ScheduledCommandWriter(
        store, json.getIfAvailable(ObjectMapper::new), ids, persimmonOutboxClock,
        traces.getIfAvailable(() -> TraceContextCarrier.NOOP));
  }

  /** Every {@link Command} class under the application's packages, by wire name and by class name. */
  @Bean
  @ConditionalOnMissingBean
  @SuppressWarnings("unchecked")
  public CommandCatalog persimmonCommandCatalog(BeanFactory beanFactory) {
    Map<String, Class<? extends Command<?>>> byType = new HashMap<>();
    for (Class<?> type : concreteClassesUnderApplication(beanFactory, Command.class)) {
      for (String key : new java.util.LinkedHashSet<>(List.of(CommandCatalog.typeOf(type), type.getName()))) {
        Class<?> previous = byType.put(key, (Class<? extends Command<?>>) type);
        if (previous != null && previous != type) {
          throw new IllegalStateException(previous.getName() + " and " + type.getName() + " both declare @CommandType " + key);
        }
      }
    }
    return type -> Optional.ofNullable(byType.get(type));
  }

  /** Every {@link IntegrationEvent} class under the application's packages, by (type, version). */
  @Bean
  @ConditionalOnMissingBean
  public IntegrationEventCatalog persimmonIntegrationEventCatalog(BeanFactory beanFactory) {
    Map<String, Class<? extends IntegrationEvent>> byKey = new HashMap<>();
    for (Class<?> type : concreteClassesUnderApplication(beanFactory, IntegrationEvent.class)) {
      IntegrationEvent.Contract contract = IntegrationEvent.contractOf(type);
      Class<?> previous = byKey.put(contract.type() + "#" + contract.version(), type.asSubclass(IntegrationEvent.class));
      if (previous != null && previous != type) {
        throw new IllegalStateException(
            previous.getName() + " and " + type.getName() + " both declare @EventType "
                + contract.type() + " v" + contract.version());
      }
    }
    return (type, version) -> Optional.ofNullable(byKey.get(type + "#" + version));
  }

  static List<Class<?>> concreteClassesUnderApplication(BeanFactory beanFactory, Class<?> marker) {
    List<Class<?>> found = new java.util.ArrayList<>();
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(marker));
    for (String pkg : AutoConfigurationPackages.get(beanFactory)) {
      for (BeanDefinition definition : scanner.findCandidateComponents(pkg)) {
        Class<?> type = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
        if (!type.isInterface()) {
          found.add(type);
        }
      }
    }
    return found;
  }

  /** Always present: the relay's dispatcher when nothing else is, and the local leg of a broker module's dispatcher, which is {@code @Primary}. */
  @Bean
  @ConditionalOnMissingBean
  public InProcessOutboxDispatcher persimmonInProcessOutboxDispatcher(
      ApplicationContext application,
      ObjectProvider<ObjectMapper> json,
      IntegrationEventCatalog catalog,
      OutboxStore store,
      PlatformTransactionManager transactionManager,
      Clock persimmonOutboxClock,
      ContextNames contexts,
      ConfigurableListableBeanFactory beanFactory,
      CommandBus commands,
      CommandCatalog commandCatalog) {
    return new InProcessOutboxDispatcher(
        application,
        json.getIfAvailable(ObjectMapper::new),
        catalog,
        store,
        new TransactionTemplate(transactionManager),
        persimmonOutboxClock,
        contexts,
        new Upcasters(upcasterBeans(beanFactory)),
        commands,
        commandCatalog);
  }

  /** Every {@link EventUpcaster} bean with its type arguments, from the bean definition first so a {@code @Bean} lambda works. */
  static List<Upcasters.Registration> upcasterBeans(ConfigurableListableBeanFactory beanFactory) {
    List<Upcasters.Registration> found = new java.util.ArrayList<>();
    for (String name : beanFactory.getBeanNamesForType(EventUpcaster.class)) {
      EventUpcaster<?, ?> bean = beanFactory.getBean(name, EventUpcaster.class);
      ResolvableType declared = beanFactory.getMergedBeanDefinition(name).getResolvableType().as(EventUpcaster.class);
      ResolvableType type = declared.getGeneric(0).resolve() != null ? declared : ResolvableType.forInstance(bean).as(EventUpcaster.class);
      Class<?> from = type.getGeneric(0).resolve();
      Class<?> to = type.getGeneric(1).resolve();
      if (from == null || to == null || from == IntegrationEvent.class) {
        throw new IllegalStateException("bean '" + name + "' must declare concrete EventUpcaster<From, To> type arguments");
      }
      found.add(new Upcasters.Registration(from, to, bean));
    }
    return found;
  }

  @Bean
  @ConditionalOnMissingBean
  public OutboxRelay persimmonOutboxRelay(
      OutboxStore store,
      OutboxDispatcher dispatcher,
      ObjectProvider<FailureClassifier> failures,
      Clock persimmonOutboxClock,
      OutboxProperties properties,
      ObjectProvider<TraceContextCarrier> traces,
      ObjectProvider<OutboxMetrics> metrics) {
    return new OutboxRelay(
        store,
        dispatcher,
        failures.getIfAvailable(() -> FailureClassifier.DEFAULT),
        new RetryBackoff(properties.retry().baseBackoff(), properties.retry().maxBackoff()),
        persimmonOutboxClock,
        properties,
        traces.getIfAvailable(() -> TraceContextCarrier.NOOP),
        metrics.getIfAvailable(() -> OutboxMetrics.NOOP));
  }

  /** With Micrometer on the classpath and a registry wired, the relay reports and the store is gauged. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
  static class Metrics {
    @Bean
    @ConditionalOnMissingBean(OutboxMetrics.class)
    @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
    OutboxMetrics persimmonOutboxMetrics(
        io.micrometer.core.instrument.MeterRegistry registry, OutboxStore store, Clock persimmonOutboxClock) {
      return new MicrometerOutboxMetrics(registry, store, persimmonOutboxClock);
    }
  }

  /** With Actuator on the classpath: {@code /actuator/outbox} for the dead letters, once exposed. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
  static class Endpoint {
    @Bean
    @ConditionalOnMissingBean
    @org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint
    OutboxEndpoint persimmonOutboxEndpoint(OutboxStore store, Clock persimmonOutboxClock) {
      return new OutboxEndpoint(store, persimmonOutboxClock);
    }
  }

  /** With Micrometer Tracing on the classpath and wired, the trace crosses the outbox hop. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
  static class Tracing {
    @Bean
    @ConditionalOnMissingBean(TraceContextCarrier.class)
    TraceContextCarrier persimmonOutboxTraceContextCarrier(
        ObjectProvider<io.micrometer.tracing.Tracer> tracer,
        ObjectProvider<io.micrometer.tracing.propagation.Propagator> propagator) {
      io.micrometer.tracing.Tracer t = tracer.getIfAvailable();
      io.micrometer.tracing.propagation.Propagator p = propagator.getIfAvailable();
      return t == null || p == null ? TraceContextCarrier.NOOP : new MicrometerTraceContextCarrier(t, p);
    }
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "persimmon.ddd.outbox", name = "relay-enabled", matchIfMissing = true)
  public RelayPoller persimmonOutboxRelayPoller(OutboxRelay relay) {
    return new RelayPoller(relay);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "persimmon.ddd.outbox.cleanup", name = "enabled", havingValue = "true")
  public CleanupPoller persimmonOutboxCleanupPoller(
      OutboxStore store, Clock persimmonOutboxClock, OutboxProperties properties) {
    return new CleanupPoller(store, persimmonOutboxClock, properties.cleanup());
  }

  /** Inbox consumers are bounded contexts: the first package under the application's base packages; the deployable's name for anything else. */
  @Bean
  @ConditionalOnMissingBean
  public ContextNames persimmonContextNames(BeanFactory beanFactory, org.springframework.core.env.Environment env) {
    return new ContextNames(
        AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory) : List.of(),
        env.getProperty("spring.application.name", "persimmon"));
  }

  public static class RelayPoller {
    private final OutboxRelay relay;

    RelayPoller(OutboxRelay relay) {
      this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${persimmon.ddd.outbox.poll-delay:1s}")
    public void poll() {
      try {
        while (relay.relay() > 0) {}
      } catch (RuntimeException e) {
        log.error("outbox poll failed; next poll continues", e);
      }
    }
  }

  public static class CleanupPoller {
    private final OutboxStore store;
    private final Clock clock;
    private final OutboxProperties.Cleanup cleanup;

    CleanupPoller(OutboxStore store, Clock clock, OutboxProperties.Cleanup cleanup) {
      this.store = store;
      this.clock = clock;
      this.cleanup = cleanup;
    }

    @Scheduled(fixedDelayString = "${persimmon.ddd.outbox.cleanup.poll-delay:1h}")
    public void purge() {
      Instant now = clock.instant();
      int deleted;
      do {
        deleted = store.deleteSentBefore(now.minus(cleanup.retention()), cleanup.batchSize());
      } while (deleted == cleanup.batchSize());
      do {
        deleted = store.deleteInboxBefore(now.minus(cleanup.inboxRetention()), cleanup.batchSize());
      } while (deleted >= cleanup.batchSize());
    }
  }
}
