# persimmon-java-ddd

Lean tactical DDD building blocks for Java 21, rewritten from `aipersimmon-ddd` one component at a
time. Group id `com.persimmon.ddd`.

| Module | What it gives you | Depends on |
|---|---|---|
| `persimmon-ddd-core` | Annotations, `AbstractAggregateRoot`, `Invariant`, `Transitions`, `ErrorCode`, `DomainException`, `IdGenerator` | nothing |
| `persimmon-ddd-application` | `Command`/`Query` + handlers and buses (`SimpleCommandBus`, `SimpleQueryBus`), `CommandContext`, `CommandInterceptor`, `UnitOfWork`, `DomainEvents`, `Slice`/`Page`/`Cursor`, application exceptions | core |
| `persimmon-ddd-web` | Spring Boot auto-configuration: RFC 9457 problem details for every `DomainException`, `ProblemCatalog` overrides per code, `X-Request-Id` filter, `Cursor` as a JSON string | application, Spring MVC |
| `persimmon-ddd-persistence-mybatis-plus` | `MybatisPlusAggregateRepository`: whole-row save with optimistic locking, events published after save; assembles `MybatisPlusInterceptor` from `InnerInterceptor` beans | application, MyBatis-Plus |
| `persimmon-ddd-spring-boot-starter` | Auto-configures `CommandBus`/`QueryBus` from handler beans, logging + validation + transaction interceptors, `UnitOfWork`, Spring-event `DomainEvents`, UUIDv7 `IdGenerator` | application, Spring Boot |

## Changes from `aipersimmon-ddd-core`

- One package `com.persimmon.ddd.core` plus `.annotation` (layer annotations moved there too).
- `InvariantViolationException` removed: `checkInvariant` throws `DomainException` with the invariant's code.
- `Specification<T>` removed: use `java.util.function.Predicate<T>`.
- `FailureSummary` removed from core: it belongs to the outbox engine and moves there with it.
- `clearDomainEvents()` removed: `drainDomainEvents()` covers it.
- `Transitions.allow(from, to, code)` no longer rejects a second code for the same destination; last one wins.
- No JaCoCo, PIT, PMD, SpotBugs or Spotless gates yet.

## Changes from `aipersimmon-ddd-application` + `aipersimmon-ddd-cqrs`

- One module, one package `com.persimmon.ddd.application`; the `page` sub-package is folded in.
- No dependency on tenancy or integration. `CommandContext` has no `tenantId`; tenancy, when it
  arrives, carries the tenant in its own context. `IntegrationEvents`, `DurableIntegrationEvents`
  and `InboundEvents` move with the integration/outbox component.
- `SimpleCommandBus` and `SimpleQueryBus` are pure Java registries with explicit `register`; the
  Spring starter will resolve handler generics and call them, instead of owning the dispatch loop.
- `CommandContexts` folded into `CommandContext` (`current()`, `runAs`).
- `ApplicationException` extends `DomainException` instead of duplicating it.
- Removed: `CommandBus.sendAs` (process manager adds it back when it lands), `CommandPrecheck`
  (write an interceptor or validate in the handler), `QueryInterceptor` (one user), `@Projection`
  (two users).

## Changes from `aipersimmon-ddd-web` + `aipersimmon-ddd-web-spring-boot-starter`

- One module that is the starter. Package `com.persimmon.ddd.web`, properties `persimmon.ddd.web.*`.
- One handler for all of `DomainException`: a `ProblemCatalog` override for the code wins, else
  the default of `DomainException.category()`. No handler per exception class.
- `WebExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`, so Spring MVC's own
  exceptions keep their standard problem details instead of being re-mapped by hand.
- `ProblemDescriptor` carries a literal title; no `MessageSource` title keys.
- Removed: `ApiException` and `ApiError` (throw a coded `DomainException`; `ProblemDetail` is the
  model), `ProblemRegistry`, `request-id.generate-if-absent` (always generated).
- Not yet ported: idempotency, rate limiting, replay protection and their stores. They come as
  their own component when needed.

## Changes from `aipersimmon-ddd-persistence-mybatis-plus` + `aipersimmon-ddd-mybatis-plus-spring-boot-starter`

- One module. The row contract is `@TableName` + `@TableId` + `@Version Long`; no `VersionedRow`.
- The repository writes `WHERE id = ? AND version = ?` and every mapped column itself, so the
  `OptimisticLockerInnerInterceptor` and the `ClearedColumns` reflection over update strategies
  are gone, and so is the post-write check that the interceptor was installed.
- A stale version throws `ConcurrencyConflictException` and a duplicate key throws
  `DuplicateEntityException` directly; no translation interceptor in the command pipeline.
- Tests run against H2 through the real mapper instead of a mocked `BaseMapper`.

## Changes from `aipersimmon-ddd-cqrs-spring-boot-starter` + `id-spring-boot-starter` + `events-spring-boot-starter`

- One starter, no properties. Package `com.persimmon.ddd.spring`.
- `SpringCommandBus` / `SpringQueryBus` fill the application module's simple buses from the
  context once all singletons exist; the message type comes from the bean definition, so a
  `@Bean` lambda handler works. The dispatch loop is not duplicated here.
- UUIDv7 is 12 lines over `SecureRandom`; the `java-uuid-generator` dependency is gone.
- Removed: `CommandTransactionGuard` (the repository already refuses to save without a
  transaction), `ConcurrencyTranslationCommandInterceptor` (persistence throws the application
  exceptions itself), `PrecheckCommandInterceptor`, `RetryOnConflictCommandInterceptor`
  (opt-in feature, add back when a project turns it on), in-process `IntegrationEvents` (moves
  with the integration component).

## Build

```
mvn -q test
```
