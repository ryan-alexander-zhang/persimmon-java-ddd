# example-app

A persimmon-ddd modular monolith. Each bounded context is a directory of five Maven modules, one
per layer; `start` assembles them into one deployable. What is not on a module's classpath cannot
be imported, so the layer arrows and the context boundaries hold at compile time.

```text
example-app/
  ordering/                     the sample context
    ordering-domain/            Order aggregate, value objects, domain events, error codes, the Orders port   -> persimmon-ddd-core only
    ordering-api/               integration events (and interfaces) other contexts may use                   -> persimmon-ddd-application
    ordering-application/       commands, queries, handlers, read models, domain event handlers              -> domain, api
    ordering-infrastructure/    MyBatis rows and mappers, Orders implementation, db/migration/ordering/      -> application, persimmon-ddd-persistence-mybatis-plus
    ordering-adapter/           REST controller, integration event listeners                                 -> application, Spring MVC
  start/                        Application, application.yaml, the persimmon starters, ArchitectureTest, OrderingFlowTest
  _context/                     the empty context `persimmon new --context <name>` copies; not a module
```

Rules a second context lives by, all checked by `ArchitectureTest`:

- a context depends on another only through that context's `-api` module: integration events and,
  for synchronous calls, an interface plus DTOs the provider implements as a bean;
- a context owns its tables and its migrations (`db/migration/<context>/V<yyyyMMddNNN>__*.sql`);
  no cross-context joins;
- contexts talk through integration events on the outbox. In one process they are delivered in
  process; the day a context becomes a service, `persimmon.ddd.outbox.routes` sends its events to
  a broker and that context gets a `start` of its own. No code changes.

`OrderingFlowTest` drives the whole thing on H2:
place, read, confirm (twice with one `Idempotency-Key`), conflict, validation, not-found, and the
outbox relaying `OrderConfirmedEvent`.

The `persimmon-ddd` modules come from GitHub Packages, which requires a token even for a public
package. Once per machine, put a server with id `github` in `~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>YOUR_GITHUB_USERNAME</username>
    <password>A_PERSONAL_ACCESS_TOKEN_WITH_read:packages</password>
  </server>
</servers>
```

```
mvn test
mvn spring-boot:run -pl start            # Swagger UI at /swagger-ui.html, spec at /v3/api-docs
curl -X POST localhost:8080/orders -H 'content-type: application/json' \
  -d '{"customerId":"c-1","lines":[{"sku":"A","quantity":2}]}'
```

Tenancy: the tables already carry `tenant_id`; `persimmon.ddd.tenancy.enabled=true` scopes them per request.

Schema: your tables are Flyway migrations under `db/migration`; the persimmon tables apply
themselves after yours through `persimmon-ddd-flyway`. To go to PostgreSQL or MySQL, swap the H2
dependency for the driver plus `flyway-database-postgresql` or `flyway-mysql`, and point
`spring.datasource` at it.
