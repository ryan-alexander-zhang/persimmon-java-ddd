package com.persimmon.ddd.openapi;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;

/** springdoc configures itself; this only adds the problem responses. {@code springdoc.*} applies as usual. */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class OpenApiAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public ProblemResponsesCustomizer persimmonProblemResponsesCustomizer() {
    return new ProblemResponsesCustomizer();
  }
}
