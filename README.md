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
3. **Inventory counter + individual tickets (consistency trade-off):** Availability is guarded by an aggregate `InventoryCounter` (fast O(1) reads for req 4.7) **and** each ticket is an individual record with its own state. Both are updated with separate conditional writes rather than a single `TransactWriteItems`. The counter is the authoritative anti-overselling guard, so **overselling is impossible** even if the two updates interleave. However, if the process crashes *between* the counter update and the ticket-state update, the two can be momentarily inconsistent (e.g. counter says reserved but a ticket is still AVAILABLE). This is an accepted trade-off for this scope:
   - **Why accepted:** the counter — not the ticket records — is the gate that prevents overselling, so the invariant that matters (never sell more than capacity) always holds. The reservation-expiry scheduler also reconciles orphaned states over time.
   - **Production hardening:** wrap the counter adjustment + ticket transitions in a single DynamoDB `TransactWriteItems` (atomic, with per-item conditions). Limited to 100 items/transaction, which is fine for the ≤10-tickets-per-purchase cap. This was deliberately left as documented future work to keep the first iteration simple.
4. **Async purchase flow:** `POST /orders` enqueues to SQS and returns `orderId` immediately (202 Accepted). A long-polling consumer processes asynchronously.
5. **Idempotency:** Consumer checks order status before mutating; `idempotencyKey` prevents duplicate orders.
6. **At-least-once SQS:** Standard queue (not FIFO). Consumer deletes message only on success; on error, visibility timeout re-delivers. DLQ recommended for production.
7. **Reservation expiry:** Scheduler runs every 60 s, releases `RESERVED → AVAILABLE` with conditional writes — safe under concurrent execution.
8. **Ticket states:** `AVAILABLE → RESERVED → PENDING_CONFIRMATION → SOLD` (final). `COMPLIMENTARY` (final, non-countable). No `USED`/QR/JWT in this scope. **Complimentary is a terminal action on a reservation** — the alternate ending to a purchase: `POST /api/v1/events/{eventId}/complimentary {reservationId, ownerRef}` takes an existing ACTIVE reservation and transitions all its tickets `RESERVED → COMPLIMENTARY`, decrementing `reservedCount` only (`availableCount` and `soldCount` untouched, so it is not a sale) and finalizing the reservation to `CONFIRMED`. This replaces the old per-`ticketId` endpoint, which required discovering an AVAILABLE ticketId with no API to do so.
9. **Eager ticket generation at event creation:** When an event (and each ticket type) is created, `CreateEventUseCase` pre-creates `totalQuantity` individual `Ticket` records in `AVAILABLE`. Every one of the five ticket states is therefore realized on a real record, and reservation expiry is a literal `RESERVED → AVAILABLE` transition on an existing row rather than a synthetic recomputation. The reservation, purchase, and expiry use cases all operate on these pre-materialized rows. For very large capacities a batched or lazy generation strategy (materializing tickets on demand, or in bounded chunks) would be used in production to cap write amplification at creation time; the eager approach is kept here because it makes the full state machine observable and correctness is the priority at this scope.
10. **Order states:** `PENDING → PROCESSING → CONFIRMED | FAILED | EXPIRED` — separate lifecycle from ticket states.
11. **No real AWS account needed:** DynamoDB Local + LocalStack run entirely offline and free.

## Running locally

The whole stack is self-provisioning. A one-shot `init-resources` container creates the
DynamoDB tables and SQS queues before the app starts.

```bash
# Build the app image + start everything (dynamodb-local, localstack, init, app)
docker-compose up --build
```

Startup order is enforced by `depends_on` conditions:
`dynamodb-local` + `localstack` become healthy → `init-resources` creates tables/queues and exits → `app` starts.

To iterate on the app without Docker (infra in containers, app on host):

```bash
# 1. Start only the infra + provisioning
docker-compose up dynamodb-local localstack init-resources

# 2. Run the app on the host (endpoints default to localhost)
./gradlew :app-service:bootRun
```

### Infrastructure provisioning — architectural note

The application **never provisions its own infrastructure**. Tables and queues are created by
`deploy/local/init-resources.sh`, run as a separate one-shot init container. This is the local
equivalent of what Terraform (IaC) would do in production, and keeps the app's runtime IAM role
restricted to data-plane permissions only (no `CreateTable`/`CreateQueue`). The script is
**idempotent** — re-running it skips existing resources. DynamoDB Local runs `-inMemory`, so data
resets on `docker-compose down` and the init container recreates a clean environment on the next
`up`.

## Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/events` | Create event with ticket types |
| GET | `/api/v1/events/{eventId}` | Get event |
| GET | `/api/v1/events/{eventId}/availability?ticketTypeId=` | Real-time availability (req 4.7) |
| POST | `/api/v1/events/{eventId}/reservations` | Reserve tickets — 10 min hold (req 4.2) |
| POST | `/api/v1/orders` | Purchase (enqueue + return orderId) (req 4.3) |
| GET | `/api/v1/orders/{orderId}` | Order status (req 4.4) |
| POST | `/api/v1/events/{eventId}/complimentary` | Mark a reservation's tickets as complimentary (audited) |

## Future extensions (out of scope)
- **Gate scanning / `USED` state:** `SOLD → USED` via idempotent conditional write. Only one scanner wins; prevents double-entry. Would require a signed QR (JWT) for offline validation.
- **Virtual threads (Project Loom):** WebFlux handles concurrency via event loop. Java 25 ships virtual threads as stable — they are an alternative for blocking-I/O workloads but are not needed in the reactive hot path.
- **Terraform IaC:** DynamoDB tables, SQS queue, ECS/Fargate service, IAM roles with least-privilege.
- **Observability:** Micrometer + Prometheus + Grafana dashboards for queue depth, reservation expiry rate, conflict rate.
