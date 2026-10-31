package com.persimmon.ddd.idempotency;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

/** The primary key is the lock: whoever inserts first owns the claim. */
public class MybatisIdempotencyStore implements IdempotencyStore {
  private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

  private final IdempotencyMapper mapper;
  private final ObjectMapper json;
  private final Clock clock;

  public MybatisIdempotencyStore(IdempotencyMapper mapper, ObjectMapper json, Clock clock) {
    this.mapper = mapper;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public Claim claim(Key key, String fingerprint, Duration lease) {
    Instant now = clock.instant();
    mapper.delete(where(key).eq("state", "COMPLETE").le("expires_at", now));
    IdempotencyRecord row = new IdempotencyRecord();
    row.setTenantId(key.tenant());
    row.setPrincipal(key.principal());
    row.setIdempotencyKey(key.value());
    row.setClaimToken(UUID.randomUUID().toString());
    row.setFingerprint(fingerprint);
    row.setState("PENDING");
    row.setCreatedAt(now);
    row.setExpiresAt(now.plus(lease));
    try {
      mapper.insert(row);
      return new Claim.Won(row.getClaimToken());
    } catch (DuplicateKeyException taken) {
      IdempotencyRecord existing = mapper.selectOne(where(key));
      if (existing == null) {
        return new Claim.InProgress();
      }
      if (!fingerprint.equals(existing.getFingerprint())) {
        return new Claim.Mismatch();
      }
      if (!"COMPLETE".equals(existing.getState())) {
        return existing.getExpiresAt().isAfter(now) ? new Claim.InProgress() : new Claim.OutcomeUnknown();
      }
      if (!existing.getExpiresAt().isAfter(now)) {
        return new Claim.InProgress();
      }
      return new Claim.Replay(new StoredResponse(existing.getResponseStatus(), existing.getResponseBody(), readHeaders(existing.getResponseHeaders())));
    }
  }

  @Override
  public void complete(Key key, String token, StoredResponse response, Duration ttl) {
    Objects.requireNonNull(token, "claim token");
    try {
      int updated = mapper.update(
          null,
          new UpdateWrapper<IdempotencyRecord>()
              .eq("tenant_id", key.tenant()).eq("principal", key.principal()).eq("idempotency_key", key.value())
              .eq("claim_token", token).eq("state", "PENDING")
              .set("state", "COMPLETE")
              .set("response_status", response.status())
              .set("response_body", response.body())
              .set("response_headers", json.writeValueAsString(response.headers()))
              .set("expires_at", clock.instant().plus(ttl)));
      if (updated != 1) {
        throw new IllegalStateException("idempotency claim no longer owned; response was not saved");
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void abandon(Key key, String token) {
    Objects.requireNonNull(token, "claim token");
    mapper.delete(where(key).eq("claim_token", token).eq("state", "PENDING"));
  }

  @Override
  public int deleteExpired(int limit) {
    Instant cutoff = clock.instant();
    List<IdempotencyRecord> expired =
        mapper.selectList(new QueryWrapper<IdempotencyRecord>().eq("state", "COMPLETE").le("expires_at", cutoff).last("LIMIT " + limit));
    int deleted = 0;
    for (IdempotencyRecord row : expired) {
      deleted += mapper.delete(where(new Key(row.getTenantId(), row.getPrincipal(), row.getIdempotencyKey()))
          .eq("state", "COMPLETE").le("expires_at", cutoff));
    }
    return deleted;
  }

  private static QueryWrapper<IdempotencyRecord> where(Key key) {
    return new QueryWrapper<IdempotencyRecord>()
        .eq("tenant_id", key.tenant()).eq("principal", key.principal()).eq("idempotency_key", key.value());
  }

  private Map<String, String> readHeaders(String stored) {
    try {
      return stored == null ? Map.of() : json.readValue(stored, HEADERS);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }
}
