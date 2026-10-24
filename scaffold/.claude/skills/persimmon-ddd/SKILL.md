---
name: persimmon-ddd
description: The conventions of this persimmon-ddd modular monolith - where each kind of class lives, how a bounded context talks to another, how to add an aggregate, command, query, integration event or scheduled command, and which checks decide. Use before writing or changing any Java under the context directories, when adding a context, or when ArchitectureTest fails.
---

# persimmon-ddd in this project

Framework: `persimmon-ddd` (`com.persimmon.ddd`). Full guide:
https://github.com/ryan-alexander-zhang/persimmon-java-ddd/blob/main/GUIDE.md. `ordering/` is the
worked example; copy its shapes.

## Where things live

A bounded context is a directory of five Maven modules and one package: `<context>/<context>-<layer>`,
package `com.example.app.<context>.<layer>`. The first package segment under `com.example.app` *is*
the context name; ArchUnit and the outbox both key on it.

| layer | holds | may depend on |
|---|---|---|
| `domain` | `@AggregateRoot` extending `AbstractAggregateRoot`, `@ValueObject` records/enums, `DomainEvent` records, `Invariant`s, an `ErrorCode` enum, `@Repository` port interfaces | `persimmon-ddd-core`, JDK |
| `api` | `IntegrationEvent` records with `@EventType(context, name, version)`, call interfaces plus record DTOs. Interfaces, records, enums only | `persimmon-ddd-application` |
| `application` | `Command<R>` / `Query<R>` records, `CommandHandler` / `QueryHandler` beans, `@ReadModel` records, read-side ports, `@DomainEventHandler` classes, implementations of this context's `api` interfaces | `domain`, `api` |
| `infrastructure` | `@TableName` rows with `@TableId` and `@Version Long`, mappers, `MybatisPlusAggregateRepository` subclasses, read-side port implementations, `src/main/resources/db/migration/<context>/V<yyyyMMddNNN>__*.sql` | `application` |
| `adapter` | `@RestController`s, `@EventListener` on `EventEnvelope<T>` for other contexts' events | `application` |

`start/` assembles the contexts: `Application`, `application.yaml`, `ArchitectureTest`,
`OrderingFlowTest`. A new context: `persimmon new --context <name>` from the project root, then
fill the packages. Never add a module by hand.

## Rules the tests enforce

- Another context is used only through its `-api` module. Never import `other.domain`,
  `other.application`, `other.infrastructure`; never read its tables.
- The domain imports nothing from Spring, MyBatis, Jackson or other persimmon modules, and never
  calls `Instant.now()`, `UUID.randomUUID()` or `new Random()`: time and ids come in as arguments.
- Aggregates hold other aggregates' ids, not the aggregates. Value objects are records or enums.
- Commands, queries and their handlers live in `application`; one handler per command; a handler
  never injects another command handler (send a command instead).
- Controllers and query results never expose an aggregate or entity; return `@ReadModel` records.
- Repository ports are interfaces in `domain`, implemented in `infrastructure`; adapters go through
  the application layer, never a repository.
- Constructor injection only. No `@Autowired` or `@Value` on fields.
- Only the persistence layer advances `version`; never call `versionAdvanced()` yourself.
- `@TableName` rows stay in `infrastructure`. `..api..` holds only interfaces, records, enums.

`mvn test` runs them all. When `ArchitectureTest` fails, the message names the rule; fix the code
to the rule, do not relax the rule.

## How to

**Aggregate.** Static factory raises events and checks invariants (`checkInvariant`,
`registerEvent`); `reconstitute` does neither and calls `restoreVersion`. State changes go through
a `Transitions<Status>` table, each disallowed move with its `ErrorCode`.

**Command.** Record implementing `Command<R>` with Jakarta validation annotations, a `@Component`
`CommandHandler<C, R>`: load through the port, call the aggregate, `save`, publish an integration
event if other contexts care (`events.publish(event, context)`), return. Throw
`EntityNotFoundException(code, message)` for a missing aggregate. The chain around a handler:
logging, validation, retry of a lost optimistic lock (3 times, fresh transactions), transaction.
Always pass the `CommandContext` on: `bus.send(child, context)`, `events.publish(event, context)`.

**Query.** Record implementing `Query<R>`, `QueryHandler<Q, R>` reading through a read-side port
(`OrderViews`) implemented in `infrastructure` straight over the tables. Never load the aggregate
to answer a query.

**Domain event handler.** `@Component @DomainEventHandler class X { @EventListener void on(E e) }`
in `application`: same transaction as the command. For reactions in another context use an
integration event instead.

**Integration event.** Record in `api`, `@EventType(context = "<context>", name = "<Name>",
version = 1)`, `subject()` returning the aggregate id. Consumer: `@EventListener void
on(EventEnvelope<TheEvent> e)` in the consuming context's `adapter`. Delivery is at least once,
per context in its own transaction; make the listener idempotent. To change the shape, bump
`version` and add an `EventUpcaster<Old, New>` bean on the consumer.

**Synchronous call across contexts.** Provider puts an interface and record DTOs in its `api`
module and implements the interface as a bean in `application` (`OrderLookup`,
`OrderLookupService`); consumer injects the interface.

**Later / deadline.** `scheduler.schedule(new Cmd(...), clock.instant().plus(d), context)` with
`CommandScheduler` and `java.time.Clock` injected; annotate the command `@CommandType("<context>.<Name>")`;
its handler checks the command still applies (status) and no-ops otherwise. No saga table.

**Table.** Migration under `<context>-infrastructure/src/main/resources/db/migration/<context>/`,
a `tenant_id VARCHAR(32) NOT NULL DEFAULT '__root__'` column, and the table listed under
`persimmon.ddd.tenancy.tables` in `start/src/main/resources/application.yaml`. Row class in
`infrastructure`.

**Error.** Add to the context's `ErrorCode` enum: stable code `<context>.<kebab-name>` and an
`ErrorCategory` (`NOT_FOUND` 404, `CONFLICT` 409, `DOMAIN_RULE` 422, `VALIDATION` 400). The web
layer turns it into a problem detail; never write a `@ExceptionHandler`.

**Endpoint.** Thin: build the command or query, send it, map the result. Validation, problem
details, `X-Request-Id`, `Idempotency-Key` and OpenAPI are already wired.

## Running

```
mvn test                              # everything, H2, ArchUnit included
mvn spring-boot:run -pl start         # /swagger-ui.html, /actuator/outbox
```

Integration events are relayed by a poller; in a test, set
`persimmon.ddd.outbox.relay-enabled=false` and call `relay.relay()` where delivery is expected.
