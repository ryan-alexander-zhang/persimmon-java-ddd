package com.persimmon.ddd.outbox.mybatis;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface OutboxMapper extends BaseMapper<OutboxRecord> {
  /** Due, unleased, under the attempt cap, and not behind an older unsent row of the same subject. */
  @Select(
      "SELECT o.event_id FROM persimmon_outbox o"
          + " WHERE o.sent = FALSE AND o.attempts < #{maxAttempts}"
          + " AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= #{now})"
          + " AND (o.lease_until IS NULL OR o.lease_until <= #{now})"
          + " AND (o.subject IS NULL OR NOT EXISTS (SELECT 1 FROM persimmon_outbox older"
          + "   WHERE older.subject = o.subject AND older.sent = FALSE"
          + "   AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id))))"
          + " ORDER BY o.created_at ASC, o.id ASC LIMIT #{batchSize}")
  List<String> selectClaimable(
      @Param("maxAttempts") int maxAttempts, @Param("now") Instant now, @Param("batchSize") int batchSize);
}
