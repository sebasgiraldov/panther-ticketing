# Verification note — Panther Ticketing defect fixes

Iteration: first (no `review.json` present). All three defects implemented from scratch
per `plan.md`. One additional real bug was found and fixed during the live happy path
(see "Additional fix" below), without which step (g) could never pass.

## Summary of changes

- **DEFECT 1 — eager ticket generation.** `CreateEventUseCase` now injects `TicketRepository`
  and, after persisting each `TicketType` + `InventoryCounter(availableCount = totalQuantity)`,
  eagerly materialises `totalQuantity` individual `Ticket` records in `AVAILABLE`
  (`id=random UUID, eventId, ticketTypeId, status=AVAILABLE, price, seatNumber=null,
  ownerRef=null, reservationId=null, version=0, createdAt=now, updatedAt=now`) via
  `ticketRepository.saveAll(List<Ticket>)`. Reactive throughout (`Flux`/`Mono`, no blocking).
  Wired through `UseCaseConfig.createEventUseCase(EventRepository, TicketRepository, TimeProvider)`.
- **DEFECT 1 / createdAt.** `CreateEventUseCase` stamps `Event.createdAt` via `TimeProvider`
  before save when null (root cause: `ApiMapper.toEvent` built the event with a non-null id and
  `createdAt=null`, so the adapter's `id==null` default branch never ran).
- **DEFECT 2 — DynamoDB GSIs.** `local-infra/init-resources.sh` creates `panther-tickets` with
  GSIs `eventId-ticketTypeId-status-index` (hash eventId, range ticketTypeId; status is a filter,
  not a key) and `reservationId-index` (hash reservationId), and `panther-orders` with
  `idempotencyKey-index` (hash idempotencyKey). All `PAY_PER_REQUEST`, `ProjectionType=ALL`,
  guarded by the existing `table_exists` idempotency check. `panther-ticket-types eventId-index`
  left unchanged.
- **DEFECT 3 — enriched GET event.** New domain aggregate `EventDetails`
  (`Event` + `List<TicketTypeAvailability{TicketType, InventoryCounter}>`). `IGetEvent.execute`
  now returns `Mono<EventDetails>`; `GetEventUseCase` composes it from
  `findTicketTypesByEventId` + `findInventoryCounter`. Web layer: new DTO
  `TicketTypeAvailabilityResponse`, `EventResponse` gains a trailing `ticketTypes` list,
  `ApiMapper.toEventResponse(EventDetails)` overload, `ApiHandler.getEvent` maps the overload.
  `createEvent` still returns the bare `Event` (empty `ticketTypes`).
- **Additional fix (real bug on the required happy path).** `POST /api/v1/orders` with a body
  omitting `idempotencyKey` 500'd with DynamoDB `Condition parameter type does not match schema
  type`: `findByIdempotencyKey(null)` queried the `idempotencyKey-index` GSI with a NULL
  AttributeValue (invalid key type). `PurchaseTicketsUseCase.execute` now falls back to
  `reservationId.toString()` when the key is null/blank — keeps the GSI key a non-null String and
  gives one order per reservation. No contract or state-machine change for valid input.
- **README.** Added Key Architectural Decision #9 (eager ticket generation; all five states on
  real records; expiry is literal `RESERVED → AVAILABLE`; batched/lazy strategy noted for very
  large capacities in production).
- **Audit preservation.** `AuditGateway.record` call sites unchanged (5 across
  `ReserveTicketsUseCase`, `ProcessOrderUseCase`, `MarkComplimentaryUseCase`,
  `ReleaseExpiredReservationsUseCase`). No audit calls removed; no new transition requiring audit.

## 1. Compile

Command (repo root):

```
./gradlew compileJava
```

Result: `BUILD SUCCESSFUL` (all modules: model, ports, dynamodb, helpers, reactive-web,
use-case, sqs, app-service). Re-run after the `PurchaseTicketsUseCase` fix: `BUILD SUCCESSFUL`.

## 2. Infrastructure — table recreation + GSI confirmation

Containers running (podman): `panther-dynamodb` (amazon/dynamodb-local, :8000, healthy),
`panther-sqs` (softwaremill/elasticmq-native, :9324, healthy). Network `panther-ticketing_default`.

Dropped the two affected tables, then re-ran the init script via the one-shot container:

```
aws dynamodb delete-table --table-name panther-tickets  --endpoint-url http://localhost:8000 --region us-east-1
aws dynamodb delete-table --table-name panther-orders   --endpoint-url http://localhost:8000 --region us-east-1

podman run --rm --network panther-ticketing_default \
  -e DYNAMODB_ENDPOINT=http://dynamodb-local:8000 -e SQS_ENDPOINT=http://sqs:9324 \
  -e AWS_REGION=us-east-1 -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy \
  -v ./local-infra:/init:ro --entrypoint /bin/sh amazon/aws-cli:latest /init/init-resources.sh
```

Init script output (relevant lines):

```
    [create] table panther-tickets (PK: id, GSIs: eventId-ticketTypeId-status-index, reservationId-index)
    [create] table panther-orders (PK: id, GSI: idempotencyKey-index)
==> Local infrastructure ready
```

`describe-table` GSI confirmation (dummy creds, region us-east-1):

`panther-tickets`:

```json
[
  { "Index": "eventId-ticketTypeId-status-index",
    "Keys": [ {"AttributeName":"eventId","KeyType":"HASH"},
              {"AttributeName":"ticketTypeId","KeyType":"RANGE"} ] },
  { "Index": "reservationId-index",
    "Keys": [ {"AttributeName":"reservationId","KeyType":"HASH"} ] }
]
```

`panther-orders`:

```json
[
  { "Index": "idempotencyKey-index",
    "Keys": [ {"AttributeName":"idempotencyKey","KeyType":"HASH"} ] }
]
```

Both tickets GSIs present; orders `idempotencyKey-index` present. Confirmed.

## 3. Request/response field names (read from DTOs before writing curl)

- `CreateEventRequest`: `name, description, date, venue, totalCapacity, ticketTypes[{name, price, totalQuantity}]`.
- `EventResponse`: `id, name, description, date, venue, totalCapacity, createdAt, ticketTypes[]`
  where each element is `TicketTypeAvailabilityResponse{id, name, price, totalQuantity, availableCount, reservedCount, soldCount}`.
- `AvailabilityResponse`: `eventId, ticketTypeId, availableCount, reservedCount, soldCount`.
- `ReserveTicketsRequest`: `ticketTypeId, quantity, customerRef`.
- `ReservationResponse`: `id, eventId, ticketTypeId, ticketIds, customerRef, expiresAt, status`.
- `PurchaseRequest`: `reservationId, customerRef, idempotencyKey` (idempotencyKey optional).
- `OrderResponse`: `id, reservationId, customerRef, quantity, totalAmount, status, createdAt, updatedAt`.

## 4. Live happy path (app via `./gradlew :app-service:bootRun`, "Started MainApplication", port 8080)

All identifiers from the live run:
`eventId=325ba61d-9864-4c20-a185-6413b97b53a9`,
`ticketTypeId=d0c7c0d3-9e07-419c-97bf-62a6074333b7`,
`reservationId=60507023-6a84-4647-8b28-415bdab95ed6`,
`orderId=10cdeade-9fa2-484e-8f10-a5b14fb284f1`.

Health: `GET /actuator/health` → HTTP 200, `"status":"UP"`.

### (a) POST /api/v1/events — HTTP 201

```json
{"id":"325ba61d-9864-4c20-a185-6413b97b53a9","name":"Concierto Panther","description":"Prueba","date":"2025-12-01T20:00:00","venue":"Estadio Manizales","totalCapacity":100,"createdAt":"2026-10-09T23:16:08.208982","ticketTypes":[]}
```

`createdAt` non-null. `ticketTypes` empty on create (by design — create returns the bare event).

### (b) 100 Ticket rows in AVAILABLE — confirmed

`scan panther-tickets` filtered by `eventId AND status=AVAILABLE`, `--select COUNT`:

```json
{ "Count": 100, "ScannedCount": 200, "ConsumedCapacity": null }
```

`Count=100` AVAILABLE for this event. (ScannedCount 200 includes 100 tickets from an earlier
throwaway event in the same table; the filter correctly isolates this event's 100.)

### (c) GET /api/v1/events/{eventId} — HTTP 200

```json
{"id":"325ba61d-9864-4c20-a185-6413b97b53a9","name":"Concierto Panther","description":"Prueba","date":"2025-12-01T20:00:00","venue":"Estadio Manizales","totalCapacity":100,"createdAt":"2026-10-09T23:16:08.208982","ticketTypes":[{"id":"d0c7c0d3-9e07-419c-97bf-62a6074333b7","name":"General","price":50000,"totalQuantity":100,"availableCount":100,"reservedCount":0,"soldCount":0}]}
```

Non-null `createdAt` AND populated `ticketTypes` with availability. Confirmed.

### (d) GET availability — HTTP 200, expected 100/0/0

```json
{"eventId":"325ba61d-9864-4c20-a185-6413b97b53a9","ticketTypeId":"d0c7c0d3-9e07-419c-97bf-62a6074333b7","availableCount":100,"reservedCount":0,"soldCount":0}
```

### (e) POST reservation (quantity 2) — HTTP 201 (previously 500)

```json
{"id":"60507023-6a84-4647-8b28-415bdab95ed6","eventId":"325ba61d-9864-4c20-a185-6413b97b53a9","ticketTypeId":"d0c7c0d3-9e07-419c-97bf-62a6074333b7","ticketIds":["9d698295-7a7d-4382-86a6-c9986775fa8c","d1e8298c-569c-4d25-b57b-97b49631f5bf"],"customerRef":"cliente-123","expiresAt":"2026-10-09T23:26:09.811861","status":"ACTIVE"}
```

Succeeds with a `reservationId` and two concrete `ticketIds`.

### (f) GET availability after reserve — HTTP 200, expected 98/2/0

```json
{"eventId":"325ba61d-9864-4c20-a185-6413b97b53a9","ticketTypeId":"d0c7c0d3-9e07-419c-97bf-62a6074333b7","availableCount":98,"reservedCount":2,"soldCount":0}
```

### (g) POST /api/v1/orders {reservationId, customerRef} — HTTP 202

```json
{"id":"10cdeade-9fa2-484e-8f10-a5b14fb284f1","reservationId":"60507023-6a84-4647-8b28-415bdab95ed6","customerRef":"cliente-123","quantity":2,"totalAmount":0,"status":"PENDING","createdAt":"2026-10-09T23:16:09.856488","updatedAt":"2026-10-09T23:16:09.85649"}
```

Returns an `orderId` immediately (HTTP 202 Accepted, status PENDING). This step previously 500'd
with `Condition parameter type does not match schema type`; fixed by the optional-idempotencyKey
fallback in `PurchaseTicketsUseCase`.

### (h) Poll GET /api/v1/orders/{orderId} — reached CONFIRMED

```
t=1s status=CONFIRMED
```

```json
{"id":"10cdeade-9fa2-484e-8f10-a5b14fb284f1","reservationId":"60507023-6a84-4647-8b28-415bdab95ed6","customerRef":"cliente-123","quantity":2,"totalAmount":0,"status":"CONFIRMED","createdAt":"2026-10-09T23:16:09.856488","updatedAt":"2026-10-09T23:16:09.938068"}
```

SQS consumer (ElasticMQ :9324) processed the order asynchronously; CONFIRMED within ~1s.

### (i) GET availability after sale — HTTP 200, expected 98/0/2

```json
{"eventId":"325ba61d-9864-4c20-a185-6413b97b53a9","ticketTypeId":"d0c7c0d3-9e07-419c-97bf-62a6074333b7","availableCount":98,"reservedCount":0,"soldCount":2}
```

`soldCount=2, reservedCount=0, availableCount=98`. Confirmed.

### 500 check

No `Unhandled error`, `500`, or exception lines for `/api/*` in the app log during the run.
Background app stopped (graceful shutdown; port 8080 free) after the suite.

## Conclusion

All three defects are fixed and verified end-to-end. `compileJava` passes, both GSI sets exist,
the full AVAILABLE → RESERVED → PENDING_CONFIRMATION → SOLD path works on real ticket records with
accurate counters, `createdAt` is non-null, and `GET /events/{id}` returns enriched ticket-type
availability. No 500s on the happy path.
