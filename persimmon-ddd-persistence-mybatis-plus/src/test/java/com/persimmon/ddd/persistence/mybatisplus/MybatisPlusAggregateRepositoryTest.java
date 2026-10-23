package com.persimmon.ddd.persistence.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.persimmon.ddd.application.ConcurrencyConflictException;
import com.persimmon.ddd.application.DomainEvents;
import com.persimmon.ddd.application.DuplicateEntityException;
import com.persimmon.ddd.core.AbstractAggregateRoot;
import com.persimmon.ddd.core.DomainEvent;
import com.persimmon.ddd.core.Identifier;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class MybatisPlusAggregateRepositoryTest {
  record ThingId(String value) implements Identifier {}

  record Relabelled(String id, String label) implements DomainEvent {}

  static final class Thing extends AbstractAggregateRoot<ThingId> {
    private final ThingId id;
    private String label;

    Thing(String id, String label, long version) {
      this.id = new ThingId(id);
      this.label = label;
      restoreVersion(version);
    }

    @Override
    public ThingId id() {
      return id;
    }

    void relabel(String label) {
      this.label = label;
      registerEvent(new Relabelled(id.value(), label));
    }
  }

  @TableName("thing")
  public static class ThingRow {
    @TableId private String id;
    private String label;
    @Version private Long version;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public Long getVersion() {
      return version;
    }

    public void setVersion(Long version) {
      this.version = version;
    }
  }

  @Mapper
  interface ThingMapper extends BaseMapper<ThingRow> {}

  static final class Things extends MybatisPlusAggregateRepository<Thing, ThingRow> {
    Things(ThingMapper mapper, DomainEvents events) {
      super(mapper, events);
    }

    void save(Thing thing) {
      saveAggregate(thing);
    }

    @Override
    protected ThingRow toRow(Thing thing) {
      ThingRow row = new ThingRow();
      row.setId(thing.id().value());
      row.setLabel(thing.label);
      return row;
    }
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    final List<DomainEvent> published = new ArrayList<>();

    @Bean
    DomainEvents domainEvents() {
      return published::add;
    }

    @Bean
    Things things(ThingMapper mapper, DomainEvents events) {
      return new Things(mapper, events);
    }
  }

  @Autowired Things things;
  @Autowired App app;
  @Autowired TransactionTemplate tx;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM thing");
    app.published.clear();
  }

  private Object[] rowOf(String id) {
    return jdbc.queryForObject(
        "SELECT label, version FROM thing WHERE id = ?",
        (rs, i) -> new Object[] {rs.getString(1), rs.getLong(2)},
        id);
  }

  @Test
  void aNewAggregateIsInsertedAtVersionOneAndItsEventsPublished() {
    Thing thing = new Thing("t-1", "first", 0);
    thing.relabel("named");
    tx.executeWithoutResult(s -> things.save(thing));
    assertEquals(List.of("named", 1L), List.of(rowOf("t-1")));
    assertEquals(1L, thing.version());
    assertEquals(List.of(new Relabelled("t-1", "named")), app.published);
    assertTrue(thing.domainEvents().isEmpty());
  }

  @Test
  void anUpdateWritesTheWholeRowNullsIncludedAndAdvancesTheVersion() {
    tx.executeWithoutResult(s -> things.save(new Thing("t-1", "first", 0)));
    Thing loaded = new Thing("t-1", "first", 1);
    loaded.relabel(null);
    tx.executeWithoutResult(s -> things.save(loaded));
    Object[] row = rowOf("t-1");
    assertNull(row[0]);
    assertEquals(2L, row[1]);
    assertEquals(2L, loaded.version());
  }

  @Test
  void aStaleVersionIsAConcurrencyConflict() {
    tx.executeWithoutResult(s -> things.save(new Thing("t-1", "first", 0)));
    tx.executeWithoutResult(s -> things.save(new Thing("t-1", "second", 1)));
    Thing stale = new Thing("t-1", "third", 1);
    assertThrows(
        ConcurrencyConflictException.class,
        () -> tx.executeWithoutResult(s -> things.save(stale)));
    assertEquals("second", rowOf("t-1")[0]);
    assertTrue(app.published.isEmpty());
  }

  @Test
  void aSecondInsertOfTheSameIdentityIsADuplicate() {
    tx.executeWithoutResult(s -> things.save(new Thing("t-1", "first", 0)));
    assertThrows(
        DuplicateEntityException.class,
        () -> tx.executeWithoutResult(s -> things.save(new Thing("t-1", "again", 0))));
  }

  @Test
  void savingOutsideATransactionIsRefused() {
    assertThrows(IllegalStateException.class, () -> things.save(new Thing("t-1", "x", 0)));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM thing", Integer.class));
  }
}
