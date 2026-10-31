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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * A request with an {@code Idempotency-Key} runs once per (tenant, principal, key). A retry with
 * the same body gets the stored response; a different body under the same key is 422; a retry
 * while the first is still running is 409. An expired pending claim returns 409 result-unknown
 * until its outcome is reconciled; it never permits automatic re-execution. Responses of 429 and 5xx are not stored, so the client
 * may retry them; a {@link ResponseRetention} bean changes which are.
 */
public class IdempotencyFilter extends OncePerRequestFilter {
  private static final Set<String> REPLAYED_HEADERS =
      Set.of("content-type", "location", "etag", "content-language");

  private final IdempotencyStore store;
  private final IdempotencyProperties properties;
  private final ObjectMapper json;
  private final ResponseRetention retention;
  private final List<ProblemCustomizer> customizers;
  private final Set<String> methods;
  private final Set<String> replayedHeaders;

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
    Key key = new Key(TenantContext.effective().value(), principal, rawKey);
    Claim claim = store.claim(key, fingerprint(buffered), properties.lease());
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
    if (retention.retain(stored)) {
      store.complete(key, token, stored, properties.ttl());
    } else {
      try {
        store.abandon(key, token);
      } catch (RuntimeException e) {
        logger.warn("could not release the idempotency claim; retries remain blocked pending reconciliation", e);
      }
    }
    wrapper.copyBodyToResponse();
  }

  /** The stored status, headers and body, marked as a replay; this request keeps its own request id. */
  private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
    response.setStatus(stored.status());
    stored.headers().forEach(response::setHeader);
    response.setHeader("Idempotent-Replayed", "true");
    response.getOutputStream().write(stored.body());
  }

  /** Written here, before MVC, so the {@link ProblemCustomizer}s are run here too. */
  private void problem(HttpServletResponse response, HttpStatus status, String type, String code, String detail) throws IOException {
    ProblemDetail body = Problems.stamp(ProblemDetail.forStatusAndDetail(status, detail));
    body.setType(URI.create(type));
    body.setProperty("code", code);
    HttpHeaders headers = new HttpHeaders();
    customizers.forEach(c -> c.customize(body, headers));
    headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), body);
  }

  /** SHA-256 over method, path, query, content type and body: what "the same request" means. */
  private static String fingerprint(BufferedRequest request) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : List.of(request.getMethod(), request.getRequestURI(), String.valueOf(request.getQueryString()), String.valueOf(request.getContentType()))) {
        digest.update(part.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
      digest.update(request.body);
      return HexFormat.of().formatHex(digest.digest());
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
