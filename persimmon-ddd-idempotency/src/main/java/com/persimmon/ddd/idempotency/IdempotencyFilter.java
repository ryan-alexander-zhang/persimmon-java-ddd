package com.persimmon.ddd.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.persimmon.ddd.application.TenantContext;
import com.persimmon.ddd.idempotency.IdempotencyStore.Claim;
import com.persimmon.ddd.idempotency.IdempotencyStore.Key;
import com.persimmon.ddd.idempotency.IdempotencyStore.StoredResponse;
import com.persimmon.ddd.web.ProblemCustomizer;
import com.persimmon.ddd.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * A request with an {@code Idempotency-Key} runs once per (tenant, principal, key); declared
 * {@code key-scope-headers} narrow the principal, so another value of one is another key. "The same
 * request" is the same method, path, query, content type, body and declared
 * {@code fingerprint-headers}. A retry of the same request gets the stored response; a different
 * request under the same key is 422; a retry
 * while the first is still running is 409. An expired pending claim returns 409 result-unknown
 * until its outcome is reconciled; it never permits automatic re-execution. Responses of 429 and 5xx are not stored, so the client
 * may retry them; a {@link ResponseRetention} bean changes which are.
 */
public class IdempotencyFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
  private static final Set<String> REPLAYED_HEADERS =
      Set.of("content-type", "location", "etag", "content-language");

  private final IdempotencyStore store;
  private final IdempotencyProperties properties;
  private final ObjectMapper json;
  private final ResponseRetention retention;
  private final List<ProblemCustomizer> customizers;
  private final Set<String> methods;
  private final Set<String> replayedHeaders;
  private final List<String> fingerprintHeaders;
  private final List<String> keyScopeHeaders;

  public IdempotencyFilter(IdempotencyStore store, IdempotencyProperties properties, ObjectMapper json, ResponseRetention retention,
      List<ProblemCustomizer> customizers) {
    this.store = store;
    this.properties = properties;
    this.json = json;
    this.retention = retention;
    this.customizers = customizers;
    this.methods = properties.methods().stream().map(m -> m.toUpperCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    this.replayedHeaders = new java.util.HashSet<>(REPLAYED_HEADERS);
    properties.replayedHeaders().forEach(h -> replayedHeaders.add(h.toLowerCase(Locale.ROOT)));
    this.fingerprintHeaders = normalized(properties.fingerprintHeaders());
    this.keyScopeHeaders = normalized(properties.keyScopeHeaders());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String rawKey = request.getHeader(properties.header());
    if (!methods.contains(request.getMethod().toUpperCase(Locale.ROOT)) || (rawKey == null || rawKey.isBlank()) && !properties.requireKey()) {
      chain.doFilter(request, response);
      return;
    }
    if (rawKey == null || rawKey.isBlank()) {
      problem(response, HttpStatus.BAD_REQUEST, "/problems/idempotency-key-required", "idempotency.key-required", properties.header() + " header is required");
      return;
    }
    if (rawKey.length() > 255) {
      problem(response, HttpStatus.BAD_REQUEST, "/problems/idempotency-key-too-long", "idempotency.key-too-long", properties.header() + " must be at most 255 characters");
      return;
    }
    BufferedRequest buffered;
    try {
      buffered = new BufferedRequest(request, (int) properties.maxBodySize().toBytes());
    } catch (BodyTooLarge tooLarge) {
      problem(response, HttpStatus.PAYLOAD_TOO_LARGE, "/problems/request-too-large", "idempotency.request-too-large", "request body exceeds " + properties.maxBodySize());
      return;
    }
    String principal = request.getUserPrincipal() == null ? "" : request.getUserPrincipal().getName();
    Key key = new Key(TenantContext.effective().value(), scoped(principal, request), rawKey);
    String fingerprint = fingerprint(buffered);
    Claim claim;
    try {
      claim = store.claim(key, fingerprint, properties.lease());
    } catch (RuntimeException e) {
      log.error("event=idempotency_decision outcome=error stage=claim error_type={}", e.getClass().getName());
      throw e;
    }
    switch (claim) {
      case Claim.Won won -> execute(buffered, response, chain, key, won.token());
      case Claim.Replay replay -> replay(response, replay.response());
      case Claim.InProgress inProgress -> {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        problem(response, HttpStatus.CONFLICT, "/problems/idempotency-in-progress", "idempotency.in-progress", "a request with this " + properties.header() + " is still being processed");
      }
      case Claim.OutcomeUnknown unknown ->
          problem(response, HttpStatus.CONFLICT, "/problems/idempotency-result-unknown", "idempotency.result-unknown",
              "the original request's outcome is unknown; reconcile it before attempting another execution");
      case Claim.Mismatch mismatch ->
          problem(response, HttpStatus.UNPROCESSABLE_ENTITY, "/problems/idempotency-key-reused", "idempotency.key-reused", "this " + properties.header() + " was used for a different request");
    }
  }

  private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain, Key key, String token)
      throws ServletException, IOException {
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    chain.doFilter(request, wrapper);
    Map<String, String> headers = new LinkedHashMap<>();
    for (String name : wrapper.getHeaderNames()) {
      if (replayedHeaders.contains(name.toLowerCase(Locale.ROOT)) && wrapper.getHeader(name) != null) {
        headers.put(name, wrapper.getHeader(name));
      }
    }
    if (wrapper.getContentType() != null) {
      headers.put(HttpHeaders.CONTENT_TYPE, wrapper.getContentType());
    }
    StoredResponse stored = new StoredResponse(wrapper.getStatus(), wrapper.getContentAsByteArray(), headers);
    boolean retain;
    try {
      retain = retention.retain(stored);
    } catch (RuntimeException e) {
      log.error("event=idempotency_record action=retention outcome=error response_status={} error_type={}",
          stored.status(), e.getClass().getName());
      throw e;
    }
    if (retain) {
      try {
        store.complete(key, token, stored, properties.ttl());
      } catch (RuntimeException e) {
        log.error("event=idempotency_record action=complete outcome=error response_status={} error_type={}",
            stored.status(), e.getClass().getName());
        throw e;
      }
      log.info("event=idempotency_record action=complete outcome=saved response_status={}", stored.status());
    } else {
      try {
        store.abandon(key, token);
      } catch (RuntimeException e) {
        log.warn("event=idempotency_record action=release outcome=error response_status={} error_type={}",
            stored.status(), e.getClass().getName());
      }
    }
    try {
      wrapper.copyBodyToResponse();
    } catch (IOException | RuntimeException e) {
      logWriteFailure("executed", stored.status(), e);
      throw e;
    }
  }

  /** The stored status, headers and body, marked as a replay; this request keeps its own request id. */
  private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
    log.info("event=idempotency_decision outcome=replay response_status={}", stored.status());
    response.setStatus(stored.status());
    stored.headers().forEach(response::setHeader);
    response.setHeader("Idempotent-Replayed", "true");
    try {
      response.getOutputStream().write(stored.body());
    } catch (IOException | RuntimeException e) {
      logWriteFailure("replay", stored.status(), e);
      throw e;
    }
  }

  /** Written here, before MVC, so the {@link ProblemCustomizer}s are run here too. */
  private void problem(HttpServletResponse response, HttpStatus status, String type, String code, String detail) throws IOException {
    log.info("event=idempotency_decision outcome=rejected code={} response_status={}", code, status.value());
    ProblemDetail body = Problems.stamp(ProblemDetail.forStatusAndDetail(status, detail));
    body.setType(URI.create(type));
    body.setProperty("code", code);
    HttpHeaders headers = new HttpHeaders();
    customizers.forEach(c -> c.customize(body, headers));
    headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    try {
      json.writeValue(response.getOutputStream(), body);
    } catch (IOException | RuntimeException e) {
      logWriteFailure("problem", status.value(), e);
      throw e;
    }
  }

  private static void logWriteFailure(String source, int status, Exception failure) {
    log.error("event=idempotency_response_write source={} outcome=error response_status={} error_type={}",
        source, status, failure.getClass().getName());
  }

  /** Lowercased, deduplicated, sorted: header order and case never matter. */
  private static List<String> normalized(List<String> headers) {
    return headers.stream().map(h -> h.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
  }

  /**
   * SHA-256 over method, path, query, content type, declared headers and body: what "the same
   * request" means. None declared hashes as before.
   */
  private String fingerprint(BufferedRequest request) {
    MessageDigest digest = sha256();
    for (String part : List.of(request.getMethod(), request.getRequestURI(), String.valueOf(request.getQueryString()), String.valueOf(request.getContentType()))) {
      digest.update(part.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '\n');
    }
    update(digest, fingerprintHeaders, request);
    digest.update(request.body);
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * The principal column, narrowed by the declared scope headers: a SHA-256 over the principal and
   * those headers, 64 hex characters whatever the principal's length. None declared, the principal
   * as it is.
   */
  private String scoped(String principal, HttpServletRequest request) {
    if (keyScopeHeaders.isEmpty()) {
      return principal;
    }
    MessageDigest digest = sha256();
    byte[] bytes = principal.getBytes(StandardCharsets.UTF_8);
    digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
    digest.update(bytes);
    update(digest, keyScopeHeaders, request);
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Each header as its name, its value count (0 when absent) and each value length-prefixed, so absent and empty differ. */
  private static void update(MessageDigest digest, List<String> names, HttpServletRequest request) {
    for (String name : names) {
      List<String> values = Collections.list(request.getHeaders(name));
      digest.update((name + '\n' + values.size() + '\n').getBytes(StandardCharsets.UTF_8));
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
      }
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static final class BodyTooLarge extends IOException {}

  /** Reads the body once, up to a limit, so it can be hashed and then read again by MVC. */
  static final class BufferedRequest extends HttpServletRequestWrapper {
    final byte[] body;

    BufferedRequest(HttpServletRequest request, int maxBytes) throws IOException {
      super(request);
      byte[] read = request.getInputStream().readNBytes(maxBytes + 1);
      if (read.length > maxBytes) {
        throw new BodyTooLarge();
      }
      this.body = read;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
          return in.read();
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
