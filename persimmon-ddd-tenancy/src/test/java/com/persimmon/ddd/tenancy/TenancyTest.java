package com.persimmon.ddd.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.persimmon.ddd.application.MissingTenantException;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.application.TenantId;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
class TenancyTest {
  @TableName("note")
  public static class NoteRow {
    @TableId private String id;
    private String text;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getText() {
      return text;
    }

    public void setText(String text) {
      this.text = text;
    }
  }

  @Mapper
  interface NoteMapper extends BaseMapper<NoteRow> {}

  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class App {
    final NoteMapper notes;

    App(NoteMapper notes) {
      this.notes = notes;
    }

    @PostMapping("/notes")
    void add(@RequestParam String id, @RequestParam String text) {
      NoteRow row = new NoteRow();
      row.setId(id);
      row.setText(text);
      notes.insert(row);
    }

    @GetMapping("/notes")
    List<String> list() {
      return notes.selectList(null).stream().map(NoteRow::getText).toList();
    }

    @GetMapping("/actuator/health")
    String health() {
      return TenantContext.current().map(TenantId::value).orElse("no tenant");
    }
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void everyStatementOnAScopedTableIsNarrowedToTheRequestsTenant() throws Exception {
    mvc.perform(post("/notes").param("id", "1").param("text", "acme-1").header("X-Tenant-Id", "acme")).andExpect(status().isOk());
    mvc.perform(post("/notes").param("id", "2").param("text", "globex-1").header("X-Tenant-Id", "globex")).andExpect(status().isOk());
    assertEquals("acme", jdbc.queryForObject("SELECT tenant_id FROM note WHERE id = '1'", String.class), "insert gets the column filled");
    mvc.perform(get("/notes").header("X-Tenant-Id", "acme")).andExpect(content().json("[\"acme-1\"]"));
    mvc.perform(get("/notes").header("X-Tenant-Id", "globex")).andExpect(content().json("[\"globex-1\"]"));
  }

  @Test
  void aRequestWithoutATenantIsRejectedExceptOnExcludedPaths() throws Exception {
    mvc.perform(get("/notes")).andExpect(status().isBadRequest());
    mvc.perform(get("/notes").header("X-Tenant-Id", "__root__")).andExpect(status().isBadRequest());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(content().string("no tenant"));
    mvc.perform(get("/actuator;/health")).andExpect(status().isBadRequest());
  }

  @Test
  void workOutsideARequestMustBindATenantExplicitly() {
    assertThrows(MissingTenantException.class, TenantContext::effective);
    assertEquals(TenantId.of("acme"), TenantContext.runAs(TenantId.of("acme"), TenantContext::effective));
    assertTrue(TenantContext.current().isEmpty());
  }

  @Test
  void anUndecidedTenantTableFailsTheGuard() {
    jdbc.update("CREATE TABLE forgotten (id INT, tenant_id VARCHAR(32))");
    try {
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class,
              () -> TenantTableGuard.verify(jdbc.getDataSource(), "tenant_id", List.of("note"), List.of()));
      assertTrue(failure.getMessage().contains("forgotten"));
      TenantTableGuard.verify(jdbc.getDataSource(), "tenant_id", List.of("note"), List.of("forgotten"));
    } finally {
      jdbc.update("DROP TABLE forgotten");
    }
  }
}
