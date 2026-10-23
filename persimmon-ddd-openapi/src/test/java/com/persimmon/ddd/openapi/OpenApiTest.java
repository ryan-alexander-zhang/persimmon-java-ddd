package com.persimmon.ddd.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.persimmon.ddd.application.ReadModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiTest {
  @ReadModel
  record OrderView(String id) {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    @GetMapping("/orders/{id}")
    OrderView get() {
      return new OrderView("o-1");
    }
  }

  @Autowired MockMvc mvc;

  @Test
  void theSpecDocumentsTheReadModelAndTheProblemResponses() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.schemas.OrderView.properties.id").exists())
        .andExpect(jsonPath("$.components.schemas.ProblemDetail.properties.code").exists())
        .andExpect(jsonPath("$.paths['/orders/{id}'].get.responses['200']").exists())
        .andExpect(jsonPath("$.paths['/orders/{id}'].get.responses['404'].content['application/problem+json']").exists())
        .andExpect(jsonPath("$.paths['/orders/{id}'].get.responses['422']").exists())
        .andExpect(jsonPath("$.paths['/orders/{id}'].get.responses['500']").exists());
  }
}
