package com.persimmon.ddd.web;

import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(WebProperties.class)
public class WebAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public Problems persimmonProblems(ObjectProvider<ProblemCatalog> catalogs) {
    return new Problems(catalogs.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  public UnavailableDependencies persimmonUnavailableDependencies(WebProperties properties) {
    Duration retryAfter = properties.unavailable().retryAfter();
    if (ClassUtils.isPresent("org.springframework.dao.DataAccessResourceFailureException", WebAutoConfiguration.class.getClassLoader())) {
      return new UnavailableDependencies(new DataAccessUnavailable(), DataAccessUnavailable::applicationException, retryAfter);
    }
    return new UnavailableDependencies(t -> false, t -> null, retryAfter);
  }

  @Bean
  @ConditionalOnMissingBean
  public WebExceptionHandler persimmonWebExceptionHandler(
      Problems problems, UnavailableDependencies unavailable, WebProperties properties) {
    return new WebExceptionHandler(problems, unavailable, properties.unavailable().code());
  }

  @Bean
  @ConditionalOnMissingBean
  public ProblemCustomizerAdvice persimmonProblemCustomizerAdvice(ObjectProvider<ProblemCustomizer> customizers) {
    return new ProblemCustomizerAdvice(customizers.orderedStream().toList());
  }

  @Bean
  @ConditionalOnMissingBean
  public CursorJacksonModule persimmonCursorJacksonModule() {
    return new CursorJacksonModule();
  }

  @Bean
  @ConditionalOnMissingBean(name = "persimmonRequestIdFilter")
  @ConditionalOnProperty(
      prefix = "persimmon.ddd.web.request-id",
      name = "enabled",
      matchIfMissing = true)
  public FilterRegistrationBean<RequestIdFilter> persimmonRequestIdFilter(WebProperties properties) {
    FilterRegistrationBean<RequestIdFilter> registration =
        new FilterRegistrationBean<>(new RequestIdFilter(properties.requestId().header()));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
    return registration;
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(ConstraintViolationException.class)
  static class ConstraintViolationConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ConstraintViolationAdvice persimmonConstraintViolationAdvice(Problems problems) {
      return new ConstraintViolationAdvice(problems);
    }
  }
}
