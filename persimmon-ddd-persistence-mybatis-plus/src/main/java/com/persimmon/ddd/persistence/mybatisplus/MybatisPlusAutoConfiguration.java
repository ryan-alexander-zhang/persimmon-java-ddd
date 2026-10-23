package com.persimmon.ddd.persistence.mybatisplus;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Assembles the one {@link MybatisPlusInterceptor} MyBatis-Plus allows from every {@link
 * InnerInterceptor} bean, in bean order. Modules contribute interceptors as beans instead of
 * fighting over the plugin.
 */
@AutoConfiguration
@ConditionalOnClass({MybatisPlusInterceptor.class, SqlSessionFactory.class})
public class MybatisPlusAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
  public MybatisPlusInterceptor persimmonMybatisPlusInterceptor(
      ObjectProvider<InnerInterceptor> innerInterceptors) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    innerInterceptors.orderedStream().forEach(interceptor::addInnerInterceptor);
    return interceptor;
  }
}
