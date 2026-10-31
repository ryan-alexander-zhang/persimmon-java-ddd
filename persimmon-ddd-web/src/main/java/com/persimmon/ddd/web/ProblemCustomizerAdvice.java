package com.persimmon.ddd.web;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Runs the {@link ProblemCustomizer}s on a problem detail Spring MVC is about to write. */
@RestControllerAdvice
public class ProblemCustomizerAdvice implements ResponseBodyAdvice<Object> {
  private final List<ProblemCustomizer> customizers;

  public ProblemCustomizerAdvice(List<ProblemCustomizer> customizers) {
    this.customizers = customizers;
  }

  @Override
  public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return !customizers.isEmpty();
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    if (body instanceof ProblemDetail problem) {
      customizers.forEach(c -> c.customize(problem, response.getHeaders()));
    }
    return body;
  }
}
