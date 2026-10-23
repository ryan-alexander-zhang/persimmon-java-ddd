package com.persimmon.ddd.spring;

import com.persimmon.ddd.core.IdGenerator;
import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 UUIDv7: millisecond timestamp first, so ids sort by creation time in an index. */
public final class Uuidv7IdGenerator implements IdGenerator {
  private final SecureRandom random = new SecureRandom();

  @Override
  public String newId() {
    long millis = System.currentTimeMillis();
    long randA = random.nextLong();
    long randB = random.nextLong();
    long msb = (millis << 16) | 0x7000L | (randA & 0x0FFFL);
    long lsb = (randB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(msb, lsb).toString();
  }
}
