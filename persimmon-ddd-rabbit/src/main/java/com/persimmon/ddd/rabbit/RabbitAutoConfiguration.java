package com.persimmon.ddd.rabbit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.persimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxProperties;
import com.persimmon.ddd.outbox.PermanentDispatchException;
import com.persimmon.ddd.outbox.TraceContextCarrier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

/**
 * With a {@link RabbitTemplate} present, routed outbox rows go to RabbitMQ; with {@code
 * persimmon.ddd.rabbit.consumer.enabled=true}, the configured queue is consumed into this
 * process's integration event listeners. Exchanges, the queue, its bindings and its dead-letter
 * queue are declared on the broker at the first connection.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
      "com.persimmon.ddd.outbox.OutboxAutoConfiguration"
    })
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnBean(InProcessOutboxDispatcher.class)
@EnableConfigurationProperties(RabbitProperties.class)
public class RabbitAutoConfiguration {
  static final String CONTAINER_FACTORY = "persimmonRabbitListenerContainerFactory";
  private static final String CONSUMER = "persimmon.ddd.rabbit.consumer";

  @Bean
  @Primary
  @ConditionalOnBean(RabbitTemplate.class)
  @ConditionalOnMissingBean(RabbitOutboxDispatcher.class)
  public OutboxDispatcher persimmonRabbitOutboxDispatcher(
      RabbitTemplate rabbit, InProcessOutboxDispatcher local, RabbitProperties properties) {
    return new RabbitOutboxDispatcher(rabbit, local, properties.sendTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = CONSUMER, name = "enabled", havingValue = "true")
  public RabbitInboundBridge persimmonRabbitInboundBridge(
      InProcessOutboxDispatcher local, ObjectProvider<TraceContextCarrier> traces) {
    return new RabbitInboundBridge(local, traces.getIfAvailable(() -> TraceContextCarrier.NOOP));
  }

  /** Transient failures retry with backoff, permanent ones are rejected at once; a rejected message goes to {@code <queue>.dlq}. */
  @Bean(CONTAINER_FACTORY)
  @ConditionalOnMissingBean(name = CONTAINER_FACTORY)
  @ConditionalOnProperty(prefix = CONSUMER, name = "enabled", havingValue = "true")
  public SimpleRabbitListenerContainerFactory persimmonRabbitListenerContainerFactory(
      SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connections, RabbitProperties properties) {
    RabbitProperties.Retry retry = properties.consumer().retry();
    ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
    backOff.setInitialInterval(retry.initialInterval().toMillis());
    backOff.setMultiplier(retry.multiplier());
    backOff.setMaxInterval(retry.maxInterval().toMillis());
    SimpleRetryPolicy policy =
        new SimpleRetryPolicy(
            Math.max(1, retry.maxAttempts()),
            Map.of(PermanentDispatchException.class, false, JsonProcessingException.class, false),
            true,
            true);
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    configurer.configure(factory, connections);
    factory.setDefaultRequeueRejected(false);
    factory.setAdviceChain(
        RetryInterceptorBuilder.stateless()
            .retryPolicy(policy)
            .backOffPolicy(backOff)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build());
    return factory;
  }

  /** One durable topic exchange per routed destination and bound exchange; with the consumer on, its queue, bindings and dead-letter queue too. */
  @Bean
  @ConditionalOnMissingBean(name = "persimmonRabbitDeclarables")
  public Declarables persimmonRabbitDeclarables(OutboxProperties outbox, RabbitProperties properties, Environment environment) {
    Set<String> exchanges = new LinkedHashSet<>(outbox.routes().values());
    List<Declarable> declarables = new ArrayList<>();
    RabbitProperties.Consumer consumer = properties.consumer();
    if (consumer.enabled()) {
      String name = consumer.queue() == null || consumer.queue().isBlank() ? environment.getProperty("spring.application.name", "persimmon") : consumer.queue();
      Queue queue = QueueBuilder.durable(name).deadLetterExchange("").deadLetterRoutingKey(name + ".dlq").build();
      declarables.add(queue);
      declarables.add(QueueBuilder.durable(name + ".dlq").build());
      exchanges.addAll(consumer.bindings().keySet());
      consumer.bindings().forEach((exchange, pattern) -> declarables.add(BindingBuilder.bind(queue).to(new TopicExchange(exchange, true, false)).with(pattern)));
    }
    exchanges.forEach(exchange -> declarables.add(new TopicExchange(exchange, true, false)));
    return new Declarables(declarables);
  }
}
