package com.persimmon.ddd.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.web.ProblemCustomizer;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** On by default once on the classpath; {@code persimmon.ddd.idempotency.enabled=false} turns it off. */
@AutoConfiguration(afterName = "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnBean(SqlSessionFactory.class)
@ConditionalOnProperty(prefix = "persimmon.ddd.idempotency", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
@EnableScheduling
public class IdempotencyAutoConfiguration {
  @Bean
  MapperFactoryBean<IdempotencyMapper> persimmonIdempotencyMapper(SqlSessionFactory factory) {
    MapperFactoryBean<IdempotencyMapper> bean = new MapperFactoryBean<>(IdempotencyMapper.class);
    bean.setSqlSessionFactory(factory);
    return bean;
  }

  @Bean
  @ConditionalOnMissingBean
  public IdempotencyStore persimmonIdempotencyStore(IdempotencyMapper mapper, ObjectProvider<ObjectMapper> json) {
    return new MybatisIdempotencyStore(mapper, json.getIfAvailable(ObjectMapper::new), Clock.systemUTC());
  }

  @Bean
  @ConditionalOnMissingBean(name = "persimmonIdempotencyFilter")
  public FilterRegistrationBean<IdempotencyFilter> persimmonIdempotencyFilter(
      IdempotencyStore store, IdempotencyProperties properties, ObjectProvider<ObjectMapper> json, ObjectProvider<ResponseRetention> retention,
      ObjectProvider<ProblemCustomizer> customizers) {
    FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(new IdempotencyFilter(
        store, properties, json.getIfAvailable(ObjectMapper::new), retention.getIfAvailable(() -> ResponseRetention.DEFAULT),
        customizers.orderedStream().toList()));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
    return registration;
  }

  @Bean
  public Purge persimmonIdempotencyPurge(IdempotencyStore store) {
    return new Purge(store);
  }

  public static class Purge {
    private final IdempotencyStore store;

    Purge(IdempotencyStore store) {
      this.store = store;
    }

    @Scheduled(fixedDelayString = "${persimmon.ddd.idempotency.purge-delay:1h}")
    public void purge() {
      while (store.deleteExpired(500) == 500) {}
    }
  }
}
