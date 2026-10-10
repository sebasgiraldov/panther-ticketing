# Panther Ticketing

Reactive backend for event ticket purchasing — built with Java 25, Spring Boot 4.x, Spring WebFlux, DynamoDB (local), and SQS (ElasticMQ, SQS-compatible). Clean/Hexagonal Architecture, Gradle multi-module.

## Stack

| Layer | Technology |
|---|---|
| Language | Java 25 (records, pattern matching, sealed) |
| Framework | Spring Boot 4.1.0-RC1 + Spring WebFlux |
| Persistence | DynamoDB Local (`amazon/dynamodb-local`) via AWS SDK v2 async |
| Messaging | SQS Standard via ElasticMQ (SQS-compatible) — at-least-once delivery |
| Build | Gradle 9.x multi-module |
| Containerization | Podman (`podman compose`) |

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
11. **No real AWS account needed:** DynamoDB Local + ElasticMQ run entirely offline and free.

## Installation

### Prerequisites

- **macOS** (Apple Silicon or Intel).
- **Homebrew** — the package manager used below. Install from <https://brew.sh> if you don't have it.
- **Java 25** — required to build and run the app.
- **Podman** + **podman-compose** — container runtime for the local infra.

### Install the toolchain

```bash
# Java 25 (Temurin)
brew install --cask temurin@25
java -version          # should report 25

# Podman + compose
brew install podman podman-compose

# Initialise and start the Podman VM (one time)
podman machine init
podman machine start
podman info             # should succeed once the machine is up
```

### Clone and build

```bash
git clone <repo-url> panther-ticketing
cd panther-ticketing

# Compile + run the ArchUnit checks + assemble
./gradlew build
```

## Execution

Two ways to run the system. Both read local configuration from `.env` (dummy AWS credentials and
local endpoints — no real AWS account needed).

### A) Everything in containers (recommended)

The whole stack is self-provisioning. A one-shot `init-resources` container creates the
DynamoDB tables and SQS queues before the app starts. Compose loads `.env` automatically via
`env_file`.

```bash
# Build the app image + start everything (dynamodb-local, sqs, dynamodb-admin, init, app)
podman compose up --build
```

Startup order is enforced by `depends_on` conditions:
`dynamodb-local` + `sqs` become healthy → `init-resources` creates tables/queues and exits → `app` starts.

Service endpoints once up:

| Service | URL |
|---|---|
| App API | <http://localhost:8080> |
| DynamoDB Local | <http://localhost:8000> |
| DynamoDB Admin (web UI) | <http://localhost:8001> |
| SQS (ElasticMQ) | <http://localhost:9324> |

### B) Infra in containers, app on the host

Useful for fast iteration on the app code.

```bash
# 1. Start only the infra + provisioning
podman compose up dynamodb-local sqs dynamodb-admin init-resources

# 2. Run the app on the host — load .env into the environment FIRST
set -a && . ./.env && set +a && ./gradlew :app-service:bootRun
```

> **Why load `.env` manually?** The app has **no hardcoded config defaults**: `application.yml`
> resolves every value with `${ENV_VAR}` and no fallback, and the `DynamoDbConfig` / `SqsConfig`
> `@Value` fields have no defaults either. `./gradlew :app-service:bootRun` does **not** read `.env`
> the way `podman compose` does through `env_file`, so running `bootRun` on its own fails to start.
> `set -a && . ./.env && set +a` exports every variable in `.env` into the shell environment before
> `bootRun`, giving the app the endpoints, table names, and queue URL it needs.

To stop and reset everything (DynamoDB Local runs `-inMemory`, so data resets):

```bash
podman compose down
```

### Infrastructure provisioning — architectural note

The application **never provisions its own infrastructure**. Tables and queues are created by
`deploy/local/init-resources.sh`, run as a separate one-shot init container. This is the local
equivalent of what Terraform (IaC) would do in production, and keeps the app's runtime IAM role
restricted to data-plane permissions only (no `CreateTable`/`CreateQueue`). The script is
**idempotent** — re-running it skips existing resources. DynamoDB Local runs `-inMemory`, so data
resets on `podman compose down` and the init container recreates a clean environment on the next
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

## Usage examples

For the full guided walkthrough see **[docs/PUESTA_EN_MARCHA.md](docs/PUESTA_EN_MARCHA.md)**, and import
**[docs/postman/panther-ticketing.postman_collection.json](docs/postman/panther-ticketing.postman_collection.json)**
into Postman for a ready-to-run collection where each request chains the previous one's IDs.

The examples below assume the stack is running (app at `http://localhost:8080`).

### Happy path: create → reserve → purchase

**1. Create an event** (with ticket types). The response includes the generated event id and the
ticket type ids.

```bash
curl --location 'http://localhost:8080/api/v1/events' \
  --header 'Content-Type: application/json' \
  --data '{
    "name": "Concierto Panther",
    "description": "Prueba",
    "date": "2026-12-01T20:00:00",
    "venue": "Estadio Manizales",
    "totalCapacity": 10,
    "ticketTypes": [
      { "name": "General", "price": 50000, "totalQuantity": 10 }
    ]
  }'
```

**2. Get the event**

```bash
curl --location 'http://localhost:8080/api/v1/events/{eventId}'
```

**3. Check availability** — right after creation the counter reads `available=10, reserved=0, sold=0`.

```bash
curl --location 'http://localhost:8080/api/v1/events/{eventId}/availability?ticketTypeId={ticketTypeId}'
```

**4. Reserve 2 tickets** — 10-minute hold. The response includes a `reservationId`.

```bash
curl --location 'http://localhost:8080/api/v1/events/{eventId}/reservations' \
  --header 'Content-Type: application/json' \
  --data '{
    "ticketTypeId": "{ticketTypeId}",
    "quantity": 2,
    "customerRef": "cliente-123"
  }'
```

After reserving 2, availability transitions to `available=8, reserved=2, sold=0`.

**5. Purchase** — enqueues the order and returns an `orderId` immediately (processed async).

```bash
curl --location 'http://localhost:8080/api/v1/orders' \
  --header 'Content-Type: application/json' \
  --data '{
    "reservationId": "{reservationId}",
    "customerRef": "cliente-123"
  }'
```

Once the consumer confirms the order, availability becomes `available=8, reserved=0, sold=2`.

**6. Check order status**

```bash
curl --location 'http://localhost:8080/api/v1/orders/{orderId}'
```

### Alternate ending: complimentary (free) tickets

Complimentary tickets go through the **same reservation flow** — instead of paying, you mark the
reservation as complimentary. There is no separate "courtesy" ticket type and no per-ticket id to
hunt for.

**1. Reserve** the tickets you want to give away (same as step 4 above) — this moves them to
`reserved`, e.g. a fresh reserve of 2 gives `available=8, reserved=2, sold=0`.

**2. Mark the reservation as complimentary** instead of purchasing it:

```bash
curl --location 'http://localhost:8080/api/v1/events/{eventId}/complimentary' \
  --header 'Content-Type: application/json' \
  --data '{
    "reservationId": "{reservationId}",
    "ownerRef": "invitado-vip"
  }'
```

This transitions the reservation's tickets `RESERVED → COMPLIMENTARY`, finalizes the reservation to
`CONFIRMED`, and **decrements `reservedCount` only** — `availableCount` and `soldCount` are
untouched, so a complimentary is **not** a sale. From the fresh reserve of 2
(`available=8, reserved=2, sold=0`) the counter becomes `available=8, reserved=0, sold=0`.

## Future extensions (out of scope)
- **Gate scanning / `USED` state:** `SOLD → USED` via idempotent conditional write. Only one scanner wins; prevents double-entry. Would require a signed QR (JWT) for offline validation.
- **Virtual threads (Project Loom):** WebFlux handles concurrency via event loop. Java 25 ships virtual threads as stable — they are an alternative for blocking-I/O workloads but are not needed in the reactive hot path.
- **Terraform IaC:** DynamoDB tables, SQS queue, ECS/Fargate service, IAM roles with least-privilege.
- **Observability:** Micrometer + Prometheus + Grafana dashboards for queue depth, reservation expiry rate, conflict rate.
