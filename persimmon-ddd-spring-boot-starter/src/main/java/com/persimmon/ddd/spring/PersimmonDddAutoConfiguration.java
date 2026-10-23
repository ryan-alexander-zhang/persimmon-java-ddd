package com.persimmon.ddd.spring;

import com.persimmon.ddd.application.CommandBus;
import com.persimmon.ddd.application.CommandInterceptor;
import com.persimmon.ddd.application.DomainEvents;
import com.persimmon.ddd.application.QueryBus;
import com.persimmon.ddd.application.UnitOfWork;
import com.persimmon.ddd.core.IdGenerator;
import jakarta.validation.Validator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every bean is {@code @ConditionalOnMissingBean}: declare your own to replace it. Without a
 * {@link PlatformTransactionManager} there is no unit of work and commands run without a
 * transaction; the aggregate repository then refuses to save.
 */
@AutoConfiguration(
    after = {
      DataSourceTransactionManagerAutoConfiguration.class,
      TransactionAutoConfiguration.class,
      ValidationAutoConfiguration.class
    })
public class PersimmonDddAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public IdGenerator persimmonIdGenerator() {
    return new Uuidv7IdGenerator();
  }

  @Bean
  @ConditionalOnMissingBean
  public DomainEvents persimmonDomainEvents(ApplicationEventPublisher publisher) {
    return new SpringDomainEvents(publisher);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(PlatformTransactionManager.class)
  public UnitOfWork persimmonUnitOfWork(PlatformTransactionManager transactionManager) {
    return new TransactionTemplateUnitOfWork(new TransactionTemplate(transactionManager));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(UnitOfWork.class)
  public TransactionCommandInterceptor persimmonTransactionCommandInterceptor(UnitOfWork unitOfWork) {
    return new TransactionCommandInterceptor(unitOfWork);
  }

  @Bean
  @ConditionalOnMissingBean
  public LoggingCommandInterceptor persimmonLoggingCommandInterceptor() {
    return new LoggingCommandInterceptor();
  }

  @Bean
  @ConditionalOnMissingBean
  public CommandBus persimmonCommandBus(
      ConfigurableListableBeanFactory beanFactory,
      ObjectProvider<CommandInterceptor> interceptors,
      IdGenerator ids) {
    return new SpringCommandBus(beanFactory, interceptors, ids);
  }

  @Bean
  @ConditionalOnMissingBean
  public QueryBus persimmonQueryBus(ConfigurableListableBeanFactory beanFactory) {
    return new SpringQueryBus(beanFactory);
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Validator.class)
  static class ValidationConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(Validator.class)
    public ValidationCommandInterceptor persimmonValidationCommandInterceptor(Validator validator) {
      return new ValidationCommandInterceptor(validator);
    }
  }
}
