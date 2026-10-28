# Building on persimmon-ddd

How a team writes a system on these modules. The `scaffold/` project (what `persimmon new`
copies) is the worked example; every class named below exists there.

## 1. Start

```
persimmon new shop --lang java --framework ddd --group io.acme     # a modular monolith, one context
cd shop && mvn test
persimmon new --context billing                                     # adds an empty context
```

Without the CLI: import `persimmon-ddd-bom`, depend on `persimmon-ddd-spring-boot-starter`,
`-web`, `-persistence-mybatis-plus`, `-outbox`, `-flyway`, and the optional modules you want.
The artifacts are on GitHub Packages; `~/.m2/settings.xml` needs a server with id `github` and a
token with `read:packages` (see `scaffold/README.md`).

## 2. A bounded context is a package and five modules

```
<context>/
  <context>-domain          aggregates, value objects, domain events, invariants, error codes, repository ports
  <context>-api             what other contexts may compile against: integration events, call interfaces, record DTOs
  <context>-application     commands, queries, handlers, read models, domain-event handlers, ports for the read side
  <context>-infrastructure  @TableName rows, mappers, repository implementations, db/migration/<context>/
  <context>-adapter         controllers, integration-event listeners, anything that talks to the outside
```

The package is the identity: `com.acme.shop.billing.*` is the `billing` context. Two things key
on that name and nothing else: `boundedContextsShouldDependOnEachOtherOnlyThroughApi` (ArchUnit)
and the outbox's per-context delivery (section 7). Keep the first package segment under the
application's base package equal to the context name.

The Maven graph enforces the layer arrows at compile time (`domain` sees `persimmon-ddd-core`
only; `application` sees `domain` and `api`; `infrastructure` and `adapter` see `application`).
`PersimmonDddRules.all()` in `ArchitectureTest` enforces what Maven cannot: no framework in the
domain, no ambient `Instant.now()`, aggregates by identity, records for value objects, handlers in
the right layer, constructor injection, and the rest. Run it from day one; it is cheaper to obey
than to retrofit.

## 3. Domain

```java
@AggregateRoot
public class Order extends AbstractAggregateRoot<OrderId> {
  private static final Transitions<OrderStatus> TRANSITIONS = Transitions.<OrderStatus>of()
      .allow(PLACED, CONFIRMED, OrderingErrorCode.ORDER_NOT_CONFIRMABLE)
      .allow(PLACED, CANCELLED, OrderingErrorCode.ORDER_NOT_CANCELLABLE);

  public static Order place(OrderId id, String customerId, List<OrderLine> lines) {
    Order order = new Order(id, customerId, lines, PLACED);
    order.checkInvariant(new HasAtLeastOneLine(lines));   // throws DomainException(EMPTY_ORDER)
    order.registerEvent(new OrderPlaced(id, customerId)); // a domain event, published after save
    return order;
  }

  public void confirm() {
    TRANSITIONS.check(status, CONFIRMED);                 // IllegalStateTransitionException, category CONFLICT
    status = CONFIRMED;
    registerEvent(new OrderConfirmed(id));
  }
}
```

- `ErrorCode` is an enum per context: a stable code (`ordering.order-not-found`) and an
  `ErrorCategory` that picks the HTTP status (`NOT_FOUND` 404, `CONFLICT` 409, `DOMAIN_RULE` 422,
  `VALIDATION` 400). Throw `DomainException` or a subclass with a code; the web module does the rest.
- Value objects are records or enums (`@ValueObject`). Identifiers implement `Identifier`.
- The repository port is an interface in the domain (`@Repository interface Orders`).
- Time and randomness come in as arguments or a `Clock`; the domain never reads them.
- A factory (`Order.place`) raises events and checks invariants; `reconstitute` does neither.

## 4. Application

A command is a record implementing `Command<R>`, validated with Jakarta annotations; one handler per
command, a Spring bean implementing `CommandHandler<C, R>`:

```java
public record ConfirmOrder(@NotBlank String orderId) implements Command<Void> {}

@Component
class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {
  public Void handle(ConfirmOrder command, CommandContext context) {
    Order order = orders.find(new OrderId(command.orderId()))
        .orElseThrow(() -> new EntityNotFoundException(ORDER_NOT_FOUND, "no order " + command.orderId()));
    order.confirm();
    orders.save(order);                                                   // publishes OrderConfirmed
    events.publish(new OrderConfirmedEvent(order.id().value(), order.customerId()), context); // outbox row
    return null;
  }
}
```

`CommandBus.send(command)` runs the interceptor chain, outermost first:

| order | interceptor | does |
|---|---|---|
| 0 | `LoggingCommandInterceptor` | one line per command with outcome and duration |
| 100 | `ValidationCommandInterceptor` | Jakarta validation; `ConstraintViolationException` is a 400 |
| 150 | `ConflictRetryCommandInterceptor` | a `ConcurrencyConflictException` (lost optimistic lock) is run again, `persimmon.ddd.command.conflict-retries` times (3), each in a fresh transaction; never inside a caller's transaction |
| 200 | `TransactionCommandInterceptor` | the handler, in one unit of work |

Your own `CommandInterceptor` bean slots in by its `order()`.

- `CommandContext` is the dispatch's identity: `messageId`, `correlationId` (shared by the whole
  causal chain), `causationId`. Pass it on: `bus.send(child, context)`,
  `events.publish(event, context)`, `scheduler.schedule(command, at, context)`.
  `CommandContext.current()` is the context of the command on this thread.
- A domain-event handler (`@DomainEventHandler` class, `@EventListener void on(OrderPlaced e)`)
  runs in the same transaction as the command, after the aggregate is saved. Use it for reactions
  inside the context; for reactions in another context, publish an integration event.
- A query is a record implementing `Query<R>` with a `QueryHandler<Q, R>`; results are
  `@ReadModel` records, never the aggregate. The read side reads tables directly through its own
  port (`OrderViews`), implemented in infrastructure. `Slice`, `Page` and `Cursor` are the paging types.
- A handler that needs the clock injects `java.time.Clock`.

## 5. Infrastructure

```java
@TableName("orders")
public class OrderRow { @TableId String id; String customerId; String status; @Version Long version; }

@Repository
class MybatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {
  public void save(Order order) { saveAggregate(order); }   // insert or UPDATE ... WHERE id = ? AND version = ?
  protected OrderRow toRow(Order order) { ... }
  protected void saveChildren(Order order) { ... }          // child tables, replaced wholesale
}
```

`saveAggregate` writes every column itself, advances the version, and publishes the aggregate's
domain events after the write. A stale version throws `ConcurrencyConflictException` (retried by
the interceptor above, else a 409); a duplicate key throws `DuplicateEntityException`.

Each context owns its tables and migrations: `src/main/resources/db/migration/<context>/V<yyyyMMddNNN>__<what>.sql`.
Spring Boot's Flyway runs them; `persimmon-ddd-flyway` then applies the framework's own tables
(`persimmon/db/migration/<component>/<vendor>`) in a history table per component. Supported
vendors: PostgreSQL, MySQL, H2. The framework never joins your tables and you never join another
context's; ArchUnit keeps `@TableName` rows inside their `-infrastructure` module.

## 6. Adapter

Controllers translate HTTP into commands and queries and nothing more:

```java
@PostMapping("/{id}/confirm")
ResponseEntity<Void> confirm(@PathVariable String id) {
  commands.send(new ConfirmOrder(id));
  return ResponseEntity.noContent().build();
}
```

- Every `DomainException` becomes an RFC 9457 problem detail with `code`, `title`, `status`,
  `detail`, `instance`, and `errors[]` for validation. Override title or status per code with a
  `ProblemCatalog` bean. Spring MVC's own errors keep their standard shape.
- `X-Request-Id` is read or generated and echoed; it is on the MDC for the request.
- `persimmon-ddd-idempotency`: a client sends `Idempotency-Key` on an unsafe request; the first
  call runs, a retry with the same body replays the stored response with
  `Idempotent-Replayed: true` (its own `X-Request-Id`; a problem body keeps the first call's
  `requestId`), a different body is 422, a concurrent duplicate is 409. Keys expire after `persimmon.ddd.idempotency.ttl` (24h). A 429 or
  5xx is not stored, so a retry with the same key runs again; a `ResponseRetention` bean decides
  otherwise (e.g. keep a 504 that says the outcome is unknown).
- `persimmon-ddd-openapi`: springdoc with the problem schema and default error responses on every
  operation; `/swagger-ui.html`.

## 7. Between contexts

**Events.** A context publishes a record from its `-api` module:

```java
@EventType(context = "ordering", name = "OrderConfirmed", version = 1)
public record OrderConfirmedEvent(String orderId, String customerId) implements IntegrationEvent {
  public String subject() { return orderId; }   // rows with one subject are delivered in order
}
```

`IntegrationEvents.publish(event, context)` writes an outbox row in the command's transaction
(wire type `ordering.OrderConfirmed`, source `ordering`). The relay polls the table, leases a
batch, and delivers. A consumer is an `@EventListener` on `EventEnvelope<OrderConfirmedEvent>` in
the consuming context's `-adapter` module; the envelope carries event id, source, tenant,
correlation and causation ids. Delivery is at least once: make listeners idempotent or let the
inbox do it (below).

**In one process** the dispatcher groups the event's listeners by bounded context and runs each
context in its own transaction with its own inbox record (`persimmon_inbox`, keyed by context,
source and event id). A failing listener in billing is retried alone, with backoff, up to
`max-attempts`, then dead-lettered; inventory's listener, which succeeded, is neither rolled back
nor run again. The monolith fails the way the split services will.

**Across processes** nothing in the code changes. Route the event types to a destination:

```yaml
persimmon.ddd.outbox.routes:
  "ordering.*": ordering.events        # exact type or a prefix ending in *
```

and add `persimmon-ddd-kafka` to both deployables; the producer side sends routed rows as
CloudEvents-style records keyed by subject, the consumer side lists
`persimmon.ddd.kafka.consumer.topics` and hands records to the same dispatcher, same listeners,
same inbox. Permanent failures (unknown type, unreadable payload) go to `<topic>.DLT`.

With RabbitMQ instead, add `persimmon-ddd-rabbit`: the destination is a topic exchange, the
routing key is the event type, and the consumer side binds its queue by pattern:

```yaml
persimmon.ddd.rabbit.consumer:
  enabled: true
  bindings:
    ordering.events: "ordering.*"      # exchange -> routing-key pattern; the queue is the app name
```

Rejected messages land in `<queue>.dlq`.

**Evolving an event.** Bump `version` and add an `EventUpcaster<OldEvent, NewEvent>` bean on the
consumer; old rows and old records are moved forward before delivery.

**Synchronous calls.** When a context needs an answer now, the provider publishes an interface
and record DTOs in its `-api` module and implements them as a bean; the consumer injects the
interface. The day the provider is a service, the consumer swaps the bean for an HTTP client of
the same interface. ArchUnit allows nothing but interfaces, records and enums in `..api..`, so
the provider's internals cannot leak into the contract.

## 8. Later, and sagas

`CommandScheduler.schedule(command, at, context)` sends a command later, durably: the row commits
with the caller's transaction and the outbox relay sends it when due, under the same tenant,
correlation and trace, with the same retries and dead letters. Give a scheduled command a stable
wire name so a rename does not strand rows already scheduled:

```java
@CommandType("ordering.CancelUnconfirmedOrder")
public record CancelUnconfirmedOrder(@NotBlank String orderId) implements Command<Void> {}
```

A saga is event handlers plus a deadline. The scaffold's `OrderPlacedHandler` schedules
`CancelUnconfirmedOrder` for `ordering.confirmation-timeout` later; its handler checks the order is
still `PLACED` before cancelling, so a confirmed order ignores it. Every reply from another context
is an integration event whose listener sends the next command. There is no saga table and no
cancel: the handler decides whether the command still applies when it arrives.

## 9. Tenancy

Off by default; every row is tenant `__root__`. To turn it on:

```yaml
persimmon.ddd.tenancy:
  enabled: true
  tables: [orders, order_line]      # scoped by tenant_id in SQL; every other table must be listed in exempt-tables
  trust-header: true                # only behind a gateway that owns X-Tenant-Id; else declare a TenantResolver bean
```

`TenantContext.effective()` is the request's tenant; outbox rows carry it and listeners run under
it; `TenantContextTaskDecorator` carries it across `@Async`. The guard refuses a table nobody
decided about, so a new table is a decision, not an accident.

## 10. Operating it

Properties, with defaults:

| property | default | meaning |
|---|---|---|
| `persimmon.ddd.outbox.batch-size` | 100 | rows one poll leases |
| `persimmon.ddd.outbox.poll-delay` | 1s | pause between polls when the last one found nothing |
| `persimmon.ddd.outbox.lease` | 5m | how long a claimed row is invisible to other instances |
| `persimmon.ddd.outbox.max-attempts` | 10 | transient failures before dead-lettering |
| `persimmon.ddd.outbox.retry.base-backoff` / `max-backoff` | 1s / 1m | exponential with jitter |
| `persimmon.ddd.outbox.cleanup.enabled` | false | purge sent rows and old inbox records |
| `persimmon.ddd.outbox.cleanup.retention` / `inbox-retention` | 7d / 30d | inbox retention must exceed the broker's |
| `persimmon.ddd.outbox.routes` | {} | type or prefix → destination |
| `persimmon.ddd.outbox.relay-enabled` | true | false in tests that call `OutboxRelay.relay()` themselves |
| `persimmon.ddd.command.conflict-retries` | 3 | optimistic-lock retries |
| `persimmon.ddd.kafka.consumer.enabled` / `topics` / `group-id` | false / [] / app name | the inbound bridge |
| `persimmon.ddd.rabbit.consumer.enabled` / `bindings` / `queue` | false / {} / app name | the inbound bridge, exchange → pattern |
| `persimmon.ddd.idempotency.ttl` / `lease` / `max-body-size` | 24h / 1m / 1MB | |
| `persimmon.ddd.tenancy.*` | off | section 9 |
| `persimmon.ddd.flyway.enabled` | true | |

Metrics (Micrometer, when a `MeterRegistry` exists): `persimmon.outbox.pending`,
`persimmon.outbox.lag.seconds` (age of the oldest unsent row), `persimmon.outbox.dead_letters`,
`persimmon.outbox.dispatch` (timer by type), `persimmon.outbox.retried`,
`persimmon.outbox.dead_lettered` (by type and reason). Alert on lag and dead letters.

Dead letters: `GET /actuator/outbox` lists them (newest first, with the last error),
`POST /actuator/outbox/{eventId}` replays one, `DELETE` discards it. Expose it with
`management.endpoints.web.exposure.include` and protect `/actuator` like any admin surface.

Throughput: one relay per instance, one batch per poll, dispatch in sequence, rows of one subject
in order. Several instances lease disjoint rows, so scale out before tuning `batch-size` down or
`poll-delay` up. A listener that does slow work should hand it off and return.

Failure semantics in one line: at least once, in order per subject, deduplicated per context by
the inbox, retried with backoff, dead-lettered with the last error, traced as a child span of the
request that wrote the row.

## 11. Testing

- Unit: aggregates and handlers are plain Java; `SimpleCommandBus` with a `List::add` lambda
  for `IntegrationEvents` is the whole harness.
- Slice: `@SpringBootTest` on H2 with `persimmon.ddd.outbox.relay-enabled=false` and
  `relay.relay()` called where the test wants delivery (`OrderingFlowTest`).
- Architecture: `ArchitectureTest` with `PersimmonDddRules.all()` and
  `boundedContextsShouldDependOnEachOtherOnlyThroughApi("<base package>")`.
- Real database: a Testcontainers `@Container @ServiceConnection` field; the framework's own
  suites run on PostgreSQL and MySQL this way.

## 12. Before production

- Swap H2 for the driver plus `flyway-database-postgresql` or `flyway-mysql`; point
  `spring.datasource` at it.
- Turn on `cleanup`; set `inbox-retention` above the broker's retention.
- Expose `/actuator/outbox` and the metrics; alert on `persimmon.outbox.lag.seconds` and
  `persimmon.outbox.dead_letters`.
- Tenancy `trust-header: true` only behind a gateway that sets the header itself; otherwise a
  `TenantResolver` bean that reads the token.
- Authentication and authorization are not in these modules: Spring Security as usual.
- Pin a released version (`v*` tag) rather than a `-SNAPSHOT`.

## 13. Known limits

- A scheduled command without `@CommandType` is stored under its class name; a rename strands
  pending rows as permanent dead letters (replay after restoring the name).
- A listener declared with `@EventListener(id = "...")` cannot be traced to its class; it is
  recorded under the deployable's name, not a context's.
- A listener registered after the context started (lazy beans) takes part from the next delivery
  on; the listener set is looked up per delivery.
- `CommandScheduler` has no cancel; the queue holds a row per deadline until it fires.
- Relay dispatch is sequential per instance.
