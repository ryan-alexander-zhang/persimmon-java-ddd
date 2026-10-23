package com.persimmon.ddd.persistence.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.persimmon.ddd.application.ConcurrencyConflictException;
import com.persimmon.ddd.application.DomainEvents;
import com.persimmon.ddd.application.DuplicateEntityException;
import com.persimmon.ddd.core.AbstractAggregateRoot;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Saves an aggregate as one whole row plus whatever {@link #saveChildren} writes, inside the
 * caller's transaction, with optimistic locking on the row's {@code @Version} column; then
 * publishes the aggregate's events.
 *
 * <p>The row class needs {@code @TableName}, {@code @TableId} and a {@code @Version Long} field.
 * The version predicate is written here, so the {@code OptimisticLockerInnerInterceptor} is not
 * needed and not used. An update writes every mapped column, nulls included, because the row is
 * the whole aggregate state, not a patch.
 */
public abstract class MybatisPlusAggregateRepository<A extends AbstractAggregateRoot<?>, D> {
  private final BaseMapper<D> mapper;
  private final DomainEvents domainEvents;

  protected MybatisPlusAggregateRepository(BaseMapper<D> mapper, DomainEvents domainEvents) {
    this.mapper = mapper;
    this.domainEvents = domainEvents;
  }

  /** Maps the aggregate's state onto a fresh row; identity included, version left alone. */
  protected abstract D toRow(A aggregate);

  /** Writes child rows after the root row; override for aggregates with more than one table. */
  protected void saveChildren(A aggregate) {}

  protected final void saveAggregate(A aggregate) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "no active transaction while saving " + describe(aggregate) + "; the row, its children"
              + " and its events must commit together. Dispatch through the CommandBus or"
              + " annotate the caller @Transactional.");
    }
    D row = toRow(aggregate);
    TableInfo table = tableOf(row);
    Object id = table.getPropertyValue(row, table.getKeyProperty());
    if (id == null) {
      throw new IllegalStateException(
          "toRow left " + describe(aggregate) + " without a primary key; map the identity onto"
              + " the row before saving");
    }
    long expected = aggregate.version();
    if (expected == 0) {
      insert(row, table, aggregate);
    } else {
      update(row, table, id, expected, aggregate);
    }
    saveChildren(aggregate);
    aggregate.versionAdvanced();
    domainEvents.publishAndClear(aggregate);
  }

  private void insert(D row, TableInfo table, A aggregate) {
    table.getConfiguration().newMetaObject(row).setValue(versionOf(table).getProperty(), 1L);
    try {
      if (mapper.insert(row) == 0) {
        throw new IllegalStateException(
            "insert of " + describe(aggregate) + " wrote zero rows; it was not saved");
      }
    } catch (DuplicateKeyException e) {
      throw new DuplicateEntityException(
          describe(aggregate) + " already exists; a concurrent create, or a reconstituted"
              + " aggregate whose factory did not call restoreVersion",
          e);
    }
  }

  private void update(D row, TableInfo table, Object id, long expected, A aggregate) {
    TableFieldInfo version = versionOf(table);
    UpdateWrapper<D> wrapper = new UpdateWrapper<>();
    wrapper.eq(table.getKeyColumn(), id).eq(version.getColumn(), expected);
    for (TableFieldInfo field : table.getFieldList()) {
      if (field != version && !field.isLogicDelete()) {
        wrapper.set(field.getColumn(), table.getPropertyValue(row, field.getProperty()));
      }
    }
    wrapper.set(version.getColumn(), expected + 1);
    if (mapper.update(null, wrapper) == 0) {
      throw new ConcurrencyConflictException(
          describe(aggregate) + " was modified concurrently (expected version " + expected + ")",
          null);
    }
  }

  private static TableInfo tableOf(Object row) {
    TableInfo table = TableInfoHelper.getTableInfo(row.getClass());
    if (table == null || table.getKeyProperty() == null) {
      throw new IllegalStateException(
          row.getClass().getName() + " is not a MyBatis-Plus row with a @TableId");
    }
    return table;
  }

  private static TableFieldInfo versionOf(TableInfo table) {
    TableFieldInfo version = table.getVersionFieldInfo();
    if (version == null) {
      throw new IllegalStateException(
          table.getEntityType().getName() + " has no @Version field, so there is no optimistic"
              + " lock to check; add one");
    }
    return version;
  }

  private static String describe(AbstractAggregateRoot<?> aggregate) {
    return aggregate.getClass().getSimpleName() + "[" + aggregate.id() + "]";
  }
}
