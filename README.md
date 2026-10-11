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

- **Java 25** — required to build and run the app.
- **Podman** — container runtime for the local infra.
- **Git**.

### Install the toolchain

**macOS** (Homebrew — install from <https://brew.sh> if needed):

```bash
brew install --cask temurin@25     # Java 25
brew install podman                # container runtime
podman machine init                # one time
podman machine start
```

**Windows** (winget):

```powershell
winget install EclipseAdoptium.Temurin.25.JDK   # Java 25
winget install RedHat.Podman                     # container runtime
podman machine init                              # one time
podman machine start
```

Verify: `java -version` reports 25 and `podman info` succeeds once the machine is up.
The Podman VM stops on reboot — resume it with `podman machine start`.

### Clone and build

**macOS / Linux**

```bash
git clone <repo-url> panther-ticketing
cd panther-ticketing
./gradlew build
```

**Windows (PowerShell)**

```powershell
git clone <repo-url> panther-ticketing
cd panther-ticketing
.\gradlew.bat build
```

## Execution

Two ways to run the system. Both read local configuration from `.env` (dummy AWS credentials and
local endpoints — no real AWS account needed).

### A) Everything in containers (recommended)

The whole stack is self-provisioning. A one-shot `init-resources` container creates the
DynamoDB tables and SQS queues before the app starts. Compose loads `.env` automatically via
`env_file`.

```bash
podman machine start          # start the Podman VM (macOS/Windows only — skip if already running)
podman compose up --build     # build image + start all services
```

Startup order is enforced by `depends_on` conditions:
`dynamodb-local` + `sqs` become healthy → `init-resources` creates tables/queues and exits → `app` starts.

To stop and reset everything:

```bash
podman compose down
```

> DynamoDB Local runs `-inMemory` — data resets on `down`. The next `up` starts with empty tables.

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
podman machine start          # start the Podman VM if not already running
podman compose up -d dynamodb-local sqs dynamodb-admin init-resources
```

Then run the app on the host:

**macOS / Linux**

```bash
./gradlew :app-service:bootRun
```

**Windows (PowerShell)**

```powershell
.\gradlew.bat :app-service:bootRun
```

To stop the infra:

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
| GET | `/api/v1/events` | List all events with availability |
| GET | `/api/v1/events/{eventId}` | Get event |
| GET | `/api/v1/events/{eventId}/availability?ticketTypeId=` | Real-time availability (req 4.7) |
| POST | `/api/v1/events/{eventId}/reservations` | Reserve tickets — 10 min hold (req 4.2) |
| POST | `/api/v1/orders` | Purchase (enqueue + return orderId) (req 4.3) |
| GET | `/api/v1/orders/{orderId}` | Order status (req 4.4) |
| POST | `/api/v1/events/{eventId}/complimentary` | Mark a reservation's tickets as complimentary (audited) |

## Usage examples

For architecture diagrams (hexagonal structure, state machines, sequence diagrams, AWS deployment) see **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**.

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

**2. List all events** — returns every event with its ticket types and current availability counters.

```bash
curl --location 'http://localhost:8080/api/v1/events'
```

Response (array):
```json
[
  {
    "id": "{eventId}",
    "name": "Concierto Panther",
    "ticketTypes": [
      {
        "id": "{ticketTypeId}",
        "name": "General",
        "availableCount": 10,
        "reservedCount": 0,
        "soldCount": 0,
        "complimentaryCount": 0
      }
    ]
  }
]
```

**3. Get the event**

```bash
curl --location 'http://localhost:8080/api/v1/events/{eventId}'
```

**4. Check availability** — right after creation the counter reads `available=10, reserved=0, sold=0, complimentary=0`.

```bash
curl --location 'http://localhost:8080/api/v1/events/{eventId}/availability?ticketTypeId={ticketTypeId}'
```

Response:
```json
{
  "eventId": "{eventId}",
  "ticketTypeId": "{ticketTypeId}",
  "availableCount": 10,
  "reservedCount": 0,
  "soldCount": 0,
  "complimentaryCount": 0
}
```

**5. Reserve 2 tickets** — 10-minute hold. The response includes a `reservationId`.

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

**6. Purchase** — enqueues the order and returns an `orderId` immediately (processed async).
The `idempotencyKey` is optional — if omitted, the system uses `reservationId + customerRef` as the key.

```bash
curl --location 'http://localhost:8080/api/v1/orders' \
  --header 'Content-Type: application/json' \
  --data '{
    "reservationId": "{reservationId}",
    "customerRef": "cliente-123"
  }'
```

Once the consumer confirms the order, availability becomes `available=8, reserved=0, sold=2, complimentary=0`.

**7. Check order status**

```bash
curl --location 'http://localhost:8080/api/v1/orders/{orderId}'
```

> **Order and ticket states after purchase:** `POST /api/v1/orders` returns immediately with `status: PENDING`. The SQS consumer processes the order asynchronously: it transitions the order `PENDING → PROCESSING → CONFIRMED` and each ticket `RESERVED → PENDING_CONFIRMATION → SOLD`. `PENDING_CONFIRMATION` is a transient internal state — under normal operation you will never observe it via the API. Poll `GET /api/v1/orders/{orderId}` until `status` is `CONFIRMED`.

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
`CONFIRMED`, and **decrements `reservedCount` and increments `complimentaryCount`** — `availableCount` and `soldCount` are
untouched, so a complimentary is **not** a sale. From the fresh reserve of 2
(`available=8, reserved=2, sold=0, complimentary=0`) the counter becomes `available=8, reserved=0, sold=0, complimentary=2`.

## AWS Deployment (production)

For production deployment on AWS, the infrastructure is fully defined as code using Terraform.
See **[deploy/terraform/README.md](deploy/terraform/README.md)** for the full setup.

The deployment flow is:

```
1. Build the app jar
   ./gradlew :app-service:bootJar

2. Build and push the Docker image to ECR
   aws ecr get-login-password --region us-east-1 | \
     docker login --username AWS --password-stdin <account>.dkr.ecr.us-east-1.amazonaws.com
   docker build -t panther-ticketing .
   docker tag panther-ticketing:latest <account>.dkr.ecr.us-east-1.amazonaws.com/panther-ticketing:latest
   docker push <account>.dkr.ecr.us-east-1.amazonaws.com/panther-ticketing:latest

3. Set the image URL in deploy/terraform/environments/<env>/terraform.tfvars
   app_image = "<account>.dkr.ecr.us-east-1.amazonaws.com/panther-ticketing:latest"

4. Apply Terraform
   cd deploy/terraform
   terraform init
   terraform apply -var-file=environments/dev/terraform.tfvars
```

What Terraform provisions: VPC + private/public subnets, ALB (HTTPS), ECS Fargate service,
DynamoDB tables (7) with GSIs, SQS queue + DLQ, IAM roles with least-privilege.
No access keys are used — the app authenticates via the ECS task role.

## Future extensions (out of scope)
- **Gate scanning / `USED` state:** `SOLD → USED` via idempotent conditional write. Only one scanner wins; prevents double-entry. Would require a signed QR (JWT) for offline validation.
- **Virtual threads (Project Loom):** WebFlux handles concurrency via event loop. Java 25 ships virtual threads as stable — they are an alternative for blocking-I/O workloads but are not needed in the reactive hot path.
- **Observability:** Micrometer + Prometheus + Grafana dashboards for queue depth, reservation expiry rate, conflict rate.
