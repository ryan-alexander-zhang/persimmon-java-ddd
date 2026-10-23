package com.persimmon.ddd.tenancy;

import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;

/**
 * Active only with {@code persimmon.ddd.tenancy.enabled=true}. Then every thread that touches data
 * must have a tenant bound: the request filter, the outbox relay and your own {@code
 * TenantContext.runAs} provide it, and {@link TenantContext#effective()} throws otherwise.
 */
@AutoConfiguration(beforeName = "com.persimmon.ddd.persistence.mybatisplus.MybatisPlusAutoConfiguration")
@ConditionalOnProperty(prefix = "persimmon.ddd.tenancy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TenancyProperties.class)
public class TenancyAutoConfiguration {
  @Bean(initMethod = "enable", destroyMethod = "disable")
  Enforcement persimmonTenantEnforcement() {
    return new Enforcement();
  }

  @Bean
  @ConditionalOnMissingBean
  public TenantResolver persimmonTenantResolver(TenancyProperties properties) {
    if (!properties.trustHeader()) {
      throw new IllegalStateException(
          "persimmon.ddd.tenancy.enabled=true needs a TenantResolver bean (a JWT claim, the host, ...)"
              + " or persimmon.ddd.tenancy.trust-header=true when a gateway you control sets '"
              + properties.header() + "' and strips it from clients");
    }
    String header = properties.header();
    return request -> java.util.Optional.ofNullable(request.getHeader(header)).filter(v -> !v.isBlank()).map(TenantId::of);
  }

  @Bean
  @ConditionalOnWebApplication(type = Type.SERVLET)
  @ConditionalOnMissingBean(name = "persimmonTenantResolutionFilter")
  public FilterRegistrationBean<TenantResolutionFilter> persimmonTenantResolutionFilter(
      TenantResolver resolver, TenancyProperties properties) {
    FilterRegistrationBean<TenantResolutionFilter> registration =
        new FilterRegistrationBean<>(
            new TenantResolutionFilter(resolver, properties.missingTenant(), properties.excludePaths()));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 15);
    return registration;
  }

  @Bean
  @ConditionalOnMissingBean(TaskDecorator.class)
  public TenantContextTaskDecorator persimmonTenantContextTaskDecorator() {
    return new TenantContextTaskDecorator();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass({TenantLineInnerInterceptor.class, SqlSessionFactory.class})
  static class SqlScoping {
    @Bean
    @ConditionalOnMissingBean
    @Order(100)
    public TenantLineInnerInterceptor persimmonTenantLineInnerInterceptor(TenancyProperties properties) {
      return new TenantLineInnerInterceptor(new TenantLineHandler(properties.column(), properties.tables()));
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    public SmartInitializingSingleton persimmonTenantTableGuard(
        ObjectProvider<DataSource> dataSource, TenancyProperties properties) {
      return () ->
          TenantTableGuard.verify(
              dataSource.getObject(), properties.column(), properties.tables(), properties.exemptTables());
    }
  }

  /** Flips the global requirement on with the context and off when it closes (tests). */
  public static final class Enforcement {
    public void enable() {
      TenantContext.setRequired(true);
    }

    public void disable() {
      TenantContext.setRequired(false);
    }
  }
}
