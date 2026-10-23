package com.persimmon.ddd.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.persimmon.ddd.outbox.InProcessOutboxDispatcher;
import com.persimmon.ddd.outbox.OutboxDispatcher;
import com.persimmon.ddd.outbox.PermanentDispatchException;
import com.persimmon.ddd.outbox.TraceContextCarrier;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * With a {@link KafkaTemplate} present, routed outbox rows go to Kafka; with {@code
 * persimmon.ddd.kafka.consumer.enabled=true}, the listed topics are consumed into this process's
 * integration event listeners.
 */
@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
      "com.persimmon.ddd.outbox.OutboxAutoConfiguration"
    })
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnBean(InProcessOutboxDispatcher.class)
@EnableConfigurationProperties(KafkaProperties.class)
@EnableKafka
public class KafkaAutoConfiguration {
  @Bean
  @Primary
  @ConditionalOnBean(KafkaTemplate.class)
  @ConditionalOnMissingBean(KafkaOutboxDispatcher.class)
  public OutboxDispatcher persimmonKafkaOutboxDispatcher(
      KafkaTemplate<String, String> kafka, InProcessOutboxDispatcher local, KafkaProperties properties) {
    return new KafkaOutboxDispatcher(kafka, local, properties.sendTimeout());
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "persimmon.ddd.kafka.consumer", name = "enabled", havingValue = "true")
  public KafkaInboundBridge persimmonKafkaInboundBridge(
      InProcessOutboxDispatcher local, ObjectProvider<TraceContextCarrier> traces) {
    return new KafkaInboundBridge(local, traces.getIfAvailable(() -> TraceContextCarrier.NOOP));
  }

  /** Transient failures retry with backoff, permanent ones go straight to {@code <topic>.DLT}. */
  @Bean
  @ConditionalOnBean(KafkaTemplate.class)
  @ConditionalOnMissingBean(CommonErrorHandler.class)
  @ConditionalOnProperty(prefix = "persimmon.ddd.kafka.consumer", name = "enabled", havingValue = "true")
  public DefaultErrorHandler persimmonKafkaErrorHandler(KafkaTemplate<String, String> kafka, KafkaProperties properties) {
    KafkaProperties.Retry retry = properties.consumer().retry();
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxAttempts());
    backOff.setInitialInterval(retry.initialInterval().toMillis());
    backOff.setMultiplier(retry.multiplier());
    backOff.setMaxInterval(retry.maxInterval().toMillis());
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            new DeadLetterPublishingRecoverer(kafka, (record, e) -> new TopicPartition(record.topic() + ".DLT", -1)), backOff);
    handler.addNotRetryableExceptions(PermanentDispatchException.class, JsonProcessingException.class);
    return handler;
  }
}
