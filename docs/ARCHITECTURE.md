# Architecture Diagrams


---

## 1. Hexagonal Architecture — Module Structure

```mermaid
graph TD
    subgraph domain["Domain (zero framework deps)"]
        model["model\nEvent · Ticket · Reservation\nOrder · AuditLog\nDomainException hierarchy"]
        ports["ports\nports.in → use case interfaces\nports.out → gateway/repository interfaces"]
        usecase["use-case\nCreateEvent · ListEvents · GetEvent\nReserveTickets · PurchaseTickets\nProcessOrder · MarkComplimentary\nReleaseExpired · CheckAvailability · GetOrderStatus"]
    end

    subgraph infra["Infrastructure"]
        web["entry-points/reactive-web\nRouterRest · ApiHandler\nApiMapper · GlobalErrorHandler\nRateLimitFilter"]
        dynamo["driven-adapters/dynamodb\nEventDynamoAdapter · TicketDynamoAdapter\nReservationDynamoAdapter · OrderDynamoAdapter\nAuditDynamoAdapter"]
        sqs["driven-adapters/sqs\nOrderQueueProducer\nOrderQueueConsumer"]
        helpers["helpers\nRequestParameterValidator"]
    end

    subgraph app["applications/app-service"]
        main["MainApplication\nUseCaseConfig\nReservationExpirationScheduler"]
    end

    model --> ports
    ports --> usecase
    web --> usecase
    dynamo --> usecase
    sqs --> usecase
    app --> domain
    app --> infra

    style domain fill:#e8f5e9,stroke:#388e3c
    style infra fill:#e3f2fd,stroke:#1976d2
    style app fill:#fff3e0,stroke:#f57c00
```

---

## 2. Ticket State Machine

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE : Event created (eager generation)

    AVAILABLE --> RESERVED : ReserveTickets\n(10 min hold)
    RESERVED --> AVAILABLE : Reservation expired\n(scheduler) or\norder failed

    RESERVED --> PENDING_CONFIRMATION : ProcessOrder\n(RESERVED → PENDING)
    PENDING_CONFIRMATION --> SOLD : ProcessOrder\n(PENDING → SOLD)\n[final]

    RESERVED --> COMPLIMENTARY : MarkComplimentary\n[final, non-countable]

    note right of SOLD : Irreversible.\nCounts toward soldCount.
    note right of COMPLIMENTARY : Irreversible.\nDoes NOT count toward soldCount.
```

---

## 3. Order State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING : PurchaseTickets\n(enqueued to SQS)
    PENDING --> PROCESSING : ProcessOrder starts
    PROCESSING --> CONFIRMED : All tickets sold,\ninventory updated
    PROCESSING --> FAILED : Reservation expired\nor error
    PROCESSING --> EXPIRED : (reserved for future\nauto-expiry of stale orders)
```

---

## 4. Rate Limiting — Request Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant RL as RateLimitFilter\n(@Order -1)
    participant GEH as GlobalErrorHandler\n(@Order -2)
    participant R as RouterFunction\n+ ApiHandler

    C->>RL: POST /reservations or POST /orders

    alt Within limit (acquirePermission = true)
        RL->>R: chain.filter(exchange)
        R-->>C: 201 / 202 (normal response)
    else Limit exceeded (acquirePermission = false)
        RL-->>C: 429 Too Many Requests\n{status:429, error:"Too Many Requests",\nmessage:"Too many requests — rate limit exceeded"}
    end
```

> `RateLimitFilter` writes the 429 JSON response directly — it does not throw an exception,
> so `GlobalErrorHandler` is not involved. Limits are configured in `application.yml`
> under `rate-limit.reservations.*` and `rate-limit.orders.*`.

---

## 4. Inventory Counter — Optimistic Locking Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant UC as ReserveTicketsUseCase
    participant DB as DynamoDB

    C->>UC: reserve(eventId, ticketTypeId, qty)
    UC->>DB: findInventoryCounter(eventId, ticketTypeId)
    DB-->>UC: counter {available=10, version=5}

    UC->>DB: UPDATE SET available=available-qty, reserved=reserved+qty, version=version+1\nWHERE version=5 AND available>=qty

    alt Condition passes (no concurrent write)
        DB-->>UC: updated counter {available=8, version=6}
        UC->>DB: findAvailableTickets(qty)
        DB-->>UC: [ticket1, ticket2]
        UC->>DB: updateTickets RESERVED (conditional)
        UC->>DB: saveReservation
        UC-->>C: Reservation (201 Created)
    else ConditionalCheckFailedException (concurrent write)
        DB-->>UC: ConditionalCheckFailedException
        UC->>UC: retry with exponential backoff\n(max 3 retries, 100ms base)
        UC->>DB: findInventoryCounter (re-read)
    end
```

---

## 5. Ticket State Machine — `PENDING_CONFIRMATION` detail

`PENDING_CONFIRMATION` is a **transient internal state** that exists only during `ProcessOrderUseCase` execution. It is never the final observable state of a ticket under normal operation.

```
POST /api/v1/orders
  → order: PENDING  (enqueued to SQS, 202 returned immediately)

SQS consumer → ProcessOrderUseCase:
  order:  PENDING → PROCESSING
  ticket: RESERVED → PENDING_CONFIRMATION   (conditional write #1)
  ticket: PENDING_CONFIRMATION → SOLD       (conditional write #2)
  counter: reservedCount--, soldCount++
  reservation: ACTIVE → CONFIRMED
  order:  PROCESSING → CONFIRMED

GET /api/v1/orders/{orderId}
  → order: CONFIRMED  (tickets already SOLD)
```

Why two writes instead of one? If the process crashes between write #1 and write #2, the ticket is stuck in `PENDING_CONFIRMATION`. The expiry scheduler only releases `RESERVED` tickets, so it will not accidentally return a partially-processed ticket to `AVAILABLE`. A manual reconciliation or a future `TransactWriteItems` migration would clean this up.

---

## 6. Async Purchase Flow (end-to-end)

```mermaid
sequenceDiagram
    participant C as Client
    participant API as ApiHandler
    participant PUC as PurchaseTicketsUseCase
    participant SQS as SQS (ElasticMQ)
    participant CON as OrderQueueConsumer
    participant PROC as ProcessOrderUseCase
    participant DB as DynamoDB

    C->>API: POST /api/v1/orders\n{reservationId, customerRef}
    API->>PUC: execute(reservationId, customerRef, idempotencyKey)
    PUC->>DB: findByIdempotencyKey → empty
    PUC->>DB: findReservation → ACTIVE, not expired
    PUC->>DB: findTicketType → price
    PUC->>DB: saveOrder (status=PENDING)
    PUC->>SQS: sendMessage {orderId}
    PUC-->>API: Order (PENDING)
    API-->>C: 202 Accepted {orderId}

    Note over CON,PROC: Async — seconds later

    CON->>SQS: receiveMessage (long poll, waitTime=20s)
    SQS-->>CON: Message {orderId}
    CON->>PROC: execute(orderId)
    PROC->>DB: findOrder → PENDING
    PROC->>DB: updateOrder → PROCESSING (conditional)
    PROC->>DB: findReservation → ACTIVE
    PROC->>DB: updateTickets RESERVED→PENDING_CONFIRMATION→SOLD
    PROC->>DB: decrementReserved, incrementSold (conditional)
    PROC->>DB: updateReservation → CONFIRMED
    PROC->>DB: updateOrder → CONFIRMED
    PROC->>DB: saveAuditLog
    CON->>SQS: deleteMessage
```

---

## 6. Reservation Expiry Flow (Scheduler)

```mermaid
sequenceDiagram
    participant SCH as ReservationExpirationScheduler
    participant UC as ReleaseExpiredReservationsUseCase
    participant DB as DynamoDB

    loop Every 60 seconds
        SCH->>UC: execute()
        UC->>DB: scan reservations WHERE status=ACTIVE AND expiresAt < now
        DB-->>UC: [reservation1, reservation2, ...]

        loop For each expired reservation
            UC->>DB: updateReservation → EXPIRED (conditional, expectedStatus=ACTIVE)
            UC->>DB: findTickets by reservationId
            UC->>DB: updateTickets RESERVED→AVAILABLE (conditional)
            UC->>DB: decrementReserved, incrementAvailable (conditional)
            UC->>DB: saveAuditLog

            Note over UC: onErrorResume per reservation —\nconcurrent scheduler instances are safe
        end
    end
```

---

## 7. Production AWS Deployment (reference)

```mermaid
graph LR
    subgraph vpc["VPC (private subnets)"]
        subgraph ecs["ECS Fargate"]
            app["panther-ticketing\napp container"]
        end
        subgraph data["Data layer"]
            ddb["DynamoDB\n(tables + GSIs)"]
            sqsq["SQS Standard Queue\n+ DLQ"]
        end
    end

    subgraph edge["Edge"]
        alb["ALB\n(HTTPS)"]
        r53["Route 53"]
    end

    subgraph ops["Ops"]
        cw["CloudWatch\nLogs + Metrics"]
        prom["Prometheus\n+ Grafana\n(future)"]
        sm["Secrets Manager\n(credentials)"]
    end

    r53 --> alb --> app
    app --> ddb
    app --> sqsq
    app --> cw
    sm -.->|inject env vars| app

    style vpc fill:#e3f2fd,stroke:#1976d2
    style edge fill:#f3e5f5,stroke:#7b1fa2
    style ops fill:#fff3e0,stroke:#f57c00
```
