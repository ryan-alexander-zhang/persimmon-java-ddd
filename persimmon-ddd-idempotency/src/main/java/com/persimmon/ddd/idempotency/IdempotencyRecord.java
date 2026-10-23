package com.persimmon.ddd.idempotency;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("persimmon_idempotency")
public class IdempotencyRecord {
  private String tenantId;
  private String principal;
  private String idempotencyKey;
  private String fingerprint;
  private String state;
  private Integer responseStatus;
  private byte[] responseBody;
  private String responseHeaders;
  private Instant createdAt;
  private Instant expiresAt;

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public String getPrincipal() {
    return principal;
  }

  public void setPrincipal(String principal) {
    this.principal = principal;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  public void setFingerprint(String fingerprint) {
    this.fingerprint = fingerprint;
  }

  public String getState() {
    return state;
  }

  public void setState(String state) {
    this.state = state;
  }

  public Integer getResponseStatus() {
    return responseStatus;
  }

  public void setResponseStatus(Integer responseStatus) {
    this.responseStatus = responseStatus;
  }

  public byte[] getResponseBody() {
    return responseBody;
  }

  public void setResponseBody(byte[] responseBody) {
    this.responseBody = responseBody;
  }

  public String getResponseHeaders() {
    return responseHeaders;
  }

  public void setResponseHeaders(String responseHeaders) {
    this.responseHeaders = responseHeaders;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }
}
