package com.persimmon.ddd.idempotency;

import com.persimmon.ddd.idempotency.IdempotencyStore.Key;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Releases of claims whose request was never handed on, or whose release failed: retried until
 * the store answers. {@link IdempotencyStore#abandon} deletes only a pending row with that token,
 * so a release that matches nothing is harmless.
 */
public class ReleaseQueue {
  private static final Logger log = LoggerFactory.getLogger(ReleaseQueue.class);

  private record Release(Key key, String token) {}

  private final IdempotencyStore store;
  // ponytail: in memory, unbounded; a release still queued when the process dies is lost and its
  // key stays pending (result-unknown after the lease), as before. A durable queue if that matters.
  private final Queue<Release> releases = new ConcurrentLinkedQueue<>();

  public ReleaseQueue(IdempotencyStore store) {
    this.store = store;
  }

  void add(Key key, String token) {
    releases.add(new Release(key, token));
  }

  int size() {
    return releases.size();
  }

  /** Tries each queued release once; stops at the first failure, which stays queued. */
  @Scheduled(fixedDelayString = "${persimmon.ddd.idempotency.release-delay:1s}")
  public void drain() {
    for (int n = releases.size(); n > 0; n--) {
      Release release = releases.poll();
      if (release == null) {
        return;
      }
      try {
        store.abandon(release.key(), release.token());
      } catch (RuntimeException e) {
        releases.add(release);
        log.warn("event=idempotency_record action=release outcome=error stage=retry queued={} error_type={}",
            releases.size(), e.getClass().getName());
        return;
      }
    }
  }
}
