# persimmon-java-ddd

Lean tactical DDD building blocks for Java 21, rewritten from `aipersimmon-ddd` one component at a
time. Group id `com.persimmon.ddd`.

| Module | What it gives you | Depends on |
|---|---|---|
| `persimmon-ddd-core` | Annotations, `AbstractAggregateRoot`, `Invariant`, `Transitions`, `ErrorCode`, `DomainException`, `IdGenerator` | nothing |
| `persimmon-ddd-application` | `Command`/`Query` + handlers and buses (`SimpleCommandBus`, `SimpleQueryBus`), `CommandContext`, `CommandInterceptor`, `UnitOfWork`, `DomainEvents`, `Slice`/`Page`/`Cursor`, application exceptions | core |

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

## Build

```
mvn -q test
```
