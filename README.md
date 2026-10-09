# Panther Ticketing

Reactive backend for event ticket purchasing — built with Java 25, Spring Boot 4.x, Spring WebFlux, DynamoDB (local), and SQS (LocalStack). Clean/Hexagonal Architecture, Gradle multi-module.

## Stack

| Layer | Technology |
|---|---|
| Language | Java 25 (records, pattern matching, sealed) |
| Framework | Spring Boot 4.1.0-RC1 + Spring WebFlux |
| Persistence | DynamoDB Local (`amazon/dynamodb-local`) via AWS SDK v2 async |
| Messaging | SQS Standard via LocalStack — at-least-once delivery |
| Build | Gradle 9.x multi-module |
| Containerization | Docker + docker-compose |

> **Runtime:** Java 25 LTS (`eclipse-temurin:25`). Language features used: records (all domain entities), pattern matching (`switch` on sealed `DomainException` hierarchy in `GlobalErrorHandler` and `MarkComplimentaryUseCase`), sealed classes (`DomainException` with `permits BusinessException, ConflictException, NotFoundException`).

## Architecture

```
panther-ticketing/
├── applications/app-service/       # Spring Boot entry point, wiring, scheduler
├── domain/
│   ├── model/                      # Pure domain records/entities — zero framework deps
│   ├── ports/                      # Interfaces: ports.in (use cases) + ports.out (gateways)
│   └── use-case/                   # Business logic — depends only on model + ports
└── infrastructure/
    ├── entry-points/reactive-web/  # WebFlux RouterFunction + ApiHandler + DTOs + error handler
    ├── driven-adapters/dynamodb/   # DynamoDbAsyncClient adapters (conditional writes, optimistic locking)
    ├── driven-adapters/sqs/        # SQS producer + long-polling consumer
    └── helpers/                    # RequestParameterValidator, shared utilities
```

### Dependency rules (enforced by ArchUnit)
- `domain/model` → no dependencies
- `domain/ports` → depends only on `model`
- `domain/use-case` → depends only on `model` + `ports`
- `infrastructure/*` → depends on `domain`; never the reverse

## Key Architectural Decisions

1. **Reactive end-to-end:** All I/O returns `Mono`/`Flux`. No blocking calls in the hot path.
2. **Anti-overselling — optimistic locking:** DynamoDB conditional writes (`ConditionExpression` + `version` attribute). No pessimistic locks, no `SELECT FOR UPDATE`.
3. **Atomic inventory:** Conditional `UpdateItem` keeps `InventoryCounter` and ticket states in sync.
4. **Async purchase flow:** `POST /orders` enqueues to SQS and returns `orderId` immediately (202 Accepted). A long-polling consumer processes asynchronously.
5. **Idempotency:** Consumer checks order status before mutating; `idempotencyKey` prevents duplicate orders.
6. **At-least-once SQS:** Standard queue (not FIFO). Consumer deletes message only on success; on error, visibility timeout re-delivers. DLQ recommended for production.
7. **Reservation expiry:** Scheduler runs every 60 s, releases `RESERVED → AVAILABLE` with conditional writes — safe under concurrent execution.
8. **Ticket states:** `AVAILABLE → RESERVED → PENDING_CONFIRMATION → SOLD` (final). `COMPLIMENTARY` (final, non-countable). No `USED`/QR/JWT in this scope.
9. **Order states:** `PENDING → PROCESSING → CONFIRMED | FAILED | EXPIRED` — separate lifecycle from ticket states.
10. **No real AWS account needed:** DynamoDB Local + LocalStack run entirely offline and free.

## Running locally (TODO — complete in environment phase)

```bash
# 1. Start infrastructure
docker-compose up dynamodb-local localstack

# 2. Create tables (init script — TODO)
# 3. Start app
./gradlew :app-service:bootRun --args='--spring.profiles.active=local'
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/events` | Create event with ticket types |
| GET | `/api/v1/events/{eventId}` | Get event |
| GET | `/api/v1/events/{eventId}/availability?ticketTypeId=` | Real-time availability (req 4.7) |
| POST | `/api/v1/events/{eventId}/reservations` | Reserve tickets — 10 min hold (req 4.2) |
| POST | `/api/v1/orders` | Purchase (enqueue + return orderId) (req 4.3) |
| GET | `/api/v1/orders/{orderId}` | Order status (req 4.4) |
| POST | `/api/v1/tickets/{ticketId}/complimentary` | Mark ticket as complimentary (audited) |

## Future extensions (out of scope)
- **Gate scanning / `USED` state:** `SOLD → USED` via idempotent conditional write. Only one scanner wins; prevents double-entry. Would require a signed QR (JWT) for offline validation.
- **Virtual threads (Project Loom):** WebFlux handles concurrency via event loop. Java 25 ships virtual threads as stable — they are an alternative for blocking-I/O workloads but are not needed in the reactive hot path.
- **Terraform IaC:** DynamoDB tables, SQS queue, ECS/Fargate service, IAM roles with least-privilege.
- **Observability:** Micrometer + Prometheus + Grafana dashboards for queue depth, reservation expiry rate, conflict rate.
