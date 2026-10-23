package com.persimmon.ddd.outbox.mybatis;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/** Row of {@code persimmon_inbox}; the key is (consumer, source, messageKey). */
@TableName("persimmon_inbox")
public class InboxRecord {
  private String consumer;
  private String source;
  private String messageKey;
  private Instant processedAt;

  public InboxRecord() {}

  public InboxRecord(String consumer, String source, String messageKey, Instant processedAt) {
    this.consumer = consumer;
    this.source = source;
    this.messageKey = messageKey;
    this.processedAt = processedAt;
  }

  public String getConsumer() {
    return consumer;
  }

  public void setConsumer(String consumer) {
    this.consumer = consumer;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getMessageKey() {
    return messageKey;
  }

  public void setMessageKey(String messageKey) {
    this.messageKey = messageKey;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  public void setProcessedAt(Instant processedAt) {
    this.processedAt = processedAt;
  }
}
