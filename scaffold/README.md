# ordering-service

A persimmon-ddd service with one bounded context, `ordering`, layered by package:

| Package | Holds | Depends on |
|---|---|---|
| `domain` | `Order` aggregate, value objects, domain events, error codes, the `Orders` port | persimmon-ddd-core, JDK |
| `api` | Integration events other contexts consume | application |
| `application` | Commands, queries, their handlers, read models, domain event handlers | domain |
| `infrastructure` | MyBatis rows, mappers, the `Orders` implementation, read-side queries | application, domain |
| `adapter` | REST controller, integration event listeners | application |

`ArchitectureTest` enforces the arrows. `OrderingFlowTest` drives the whole thing on H2:
place, read, confirm (twice with one `Idempotency-Key`), conflict, validation, not-found, and the
outbox relaying `OrderConfirmedEvent`.

```
mvn test
mvn spring-boot:run          # Swagger UI at /swagger-ui.html, spec at /v3/api-docs
curl -X POST localhost:8080/orders -H 'content-type: application/json' \
  -d '{"customerId":"c-1","lines":[{"sku":"A","quantity":2}]}'
```

Tenancy: the tables already carry `tenant_id`; `persimmon.ddd.tenancy.enabled=true` scopes them per request.

Schema: your tables are Flyway migrations under `db/migration`; the persimmon tables apply
themselves after yours through `persimmon-ddd-flyway`. To go to PostgreSQL or MySQL, swap the H2
dependency for the driver plus `flyway-database-postgresql` or `flyway-mysql`, and point
`spring.datasource` at it.
