package com.persimmon.ddd.web;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.persimmon.ddd.application.ConcurrencyConflictException;
import com.persimmon.ddd.application.Cursor;
import com.persimmon.ddd.application.EntityNotFoundException;
import com.persimmon.ddd.application.Slice;
import com.persimmon.ddd.core.DomainException;
import com.persimmon.ddd.core.ErrorCode;
import com.persimmon.ddd.core.IllegalStateTransitionException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
class WebLayerTest {
  @Autowired MockMvc mvc;

  enum Code implements ErrorCode {
    CREDIT_EXCEEDED("ordering.credit-exceeded"),
    ORDER_REJECTED("ordering.order-rejected");

    private final String code;

    Code(String code) {
      this.code = code;
    }

    @Override
    public String code() {
      return code;
    }
  }

  record Body(@NotBlank String name) {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    @Bean
    ProblemCatalog catalog() {
      return () ->
          Map.of(
              Code.CREDIT_EXCEEDED,
              new ProblemDescriptor("/problems/insufficient-credit", 402, "Insufficient credit"));
    }

    @Bean
    ProblemCustomizer source() {
      return (problem, headers) -> {
        problem.setProperty("source", "app");
        headers.set("X-Source", "app");
      };
    }

    @PostMapping("/t/coded")
    void coded() {
      throw new DomainException(Code.CREDIT_EXCEEDED, "over limit");
    }

    @PostMapping("/t/family")
    void family() {
      throw new DomainException(Code.ORDER_REJECTED, "rejected");
    }

    @PostMapping("/t/not-found")
    void notFound() {
      throw new EntityNotFoundException("no such order");
    }

    @PostMapping("/t/conflict")
    void conflict() {
      throw new ConcurrencyConflictException("someone else first", null);
    }

    @PostMapping("/t/transition")
    void transition() {
      throw new IllegalStateTransitionException(null, "PENDING", "SHIPPED");
    }

    @PostMapping("/t/valid")
    void valid(@Valid @RequestBody Body body) {}

    @GetMapping("/t/param")
    void param(@RequestParam @NotBlank String q) {}

    @GetMapping("/t/violation")
    void violation() {
      throw new ConstraintViolationException("bad", Set.of());
    }

    @GetMapping("/t/boom")
    void boom() {
      throw new IllegalStateException("secret internals");
    }

    @GetMapping("/t/slice")
    Slice<String> slice() {
      return new Slice<>(List.of("a"), new Cursor("next-1"));
    }
  }

  @Test
  void codedExceptionWithCatalogOverrideUsesIt() throws Exception {
    mvc.perform(post("/t/coded"))
        .andExpect(status().is(402))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("/problems/insufficient-credit"))
        .andExpect(jsonPath("$.title").value("Insufficient credit"))
        .andExpect(jsonPath("$.detail").value("over limit"))
        .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"))
        .andExpect(jsonPath("$.requestId").value(notNullValue()))
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void codedExceptionWithoutOverrideUsesTheCategoryDefault() throws Exception {
    mvc.perform(post("/t/family"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.type").value("/problems/domain-rule-violation"))
        .andExpect(jsonPath("$.code").value("ordering.order-rejected"));
  }

  @Test
  void uncodedExceptionsMapByTheirFallbackCategory() throws Exception {
    mvc.perform(post("/t/not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").doesNotExist());
    mvc.perform(post("/t/conflict")).andExpect(status().isConflict());
    mvc.perform(post("/t/transition")).andExpect(status().isConflict());
  }

  @Test
  void validationFailuresListFieldErrors() throws Exception {
    mvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Validation failed"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].code").value("NotBlank"))
        .andExpect(jsonPath("$.requestId").value(notNullValue()));
    mvc.perform(get("/t/param").param("q", " "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("q"));
    mvc.perform(get("/t/violation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Validation failed"));
  }

  @Test
  void springMvcExceptionsKeepTheirStatusAndGetARequestId() throws Exception {
    mvc.perform(get("/t/nowhere"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.requestId").value(notNullValue()));
    mvc.perform(get("/t/coded")).andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/t/valid").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unexpectedExceptionIs500WithoutInternals() throws Exception {
    mvc.perform(get("/t/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").doesNotExist())
        .andExpect(jsonPath("$.requestId").value(notNullValue()));
  }

  @Test
  void requestIdIsEchoedWhenGiven() throws Exception {
    mvc.perform(post("/t/family").header("X-Request-Id", "req-42"))
        .andExpect(header().string("X-Request-Id", "req-42"))
        .andExpect(jsonPath("$.requestId").value("req-42"));
  }

  @Test
  void aProblemCustomizerShapesEveryProblemSpringMvcWrites() throws Exception {
    mvc.perform(post("/t/coded"))
        .andExpect(header().string("X-Source", "app"))
        .andExpect(jsonPath("$.source").value("app"))
        .andExpect(jsonPath("$.code").value("ordering.credit-exceeded"));
    mvc.perform(get("/t/nowhere")).andExpect(header().string("X-Source", "app")).andExpect(jsonPath("$.source").value("app"));
    mvc.perform(get("/t/boom")).andExpect(header().string("X-Source", "app")).andExpect(jsonPath("$.source").value("app"));
    mvc.perform(get("/t/violation")).andExpect(header().string("X-Source", "app")).andExpect(jsonPath("$.source").value("app"));
    mvc.perform(get("/t/slice")).andExpect(header().doesNotExist("X-Source"));
  }

  @Test
  void cursorIsAPlainString() throws Exception {
    mvc.perform(get("/t/slice"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0]").value("a"))
        .andExpect(jsonPath("$.nextCursor").value("next-1"));
  }
}
