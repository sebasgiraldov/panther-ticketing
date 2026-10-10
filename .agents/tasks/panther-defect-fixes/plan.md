# Implementation Plan — Panther Ticketing defect fixes

Fix three end-to-end defects in the reactive backend. Repo root:
`/Users/sebastian.giraldo/Documents/personal/test/panther-ticketing`.
Gradle multi-module, Spring Boot 4 / WebFlux / Java 25, DynamoDB Local + ElasticMQ (SQS).

## Design decisions (made here, grounded in the code)

- **Eager ticket generation is the chosen design** (decided with the stakeholder, backed by `test/prueba.txt` §4.6 which requires expired reservations to return tickets to AVAILABLE). On event creation, `CreateEventUseCase` pre-creates `totalQuantity` individual `Ticket` records in `AVAILABLE` per ticket type. Lazy creation is explicitly rejected. Rationale: makes all five ticket states real on actual records and makes reservation expiry a literal `RESERVED -> AVAILABLE` transition. The existing `ReserveTicketsUseCase`, `ProcessOrderUseCase`, and `ReleaseExpiredReservationsUseCase` already assume AVAILABLE rows exist (they query `findByEventIdAndTicketTypeIdAndStatus(..., AVAILABLE, ...)`), so pre-generation is what makes them correct — no rewrite of those use cases.
- **`createdAt` stamped in the use case via `TimeProvider`**, not in the adapter. Rationale: `ApiMapper.toEvent` builds the `Event` with a non-null random `id` and `createdAt=null`; `EventDynamoAdapter.save` only defaults `createdAt` when `id==null`, so that branch never runs and `createdAt` persists as null. Stamping in `CreateEventUseCase` keeps time control in the domain layer (where `TimeProvider` already lives) and fixes the root cause once for both the persisted value and the response.
- **GET event enrichment composed in the use case.** `IGetEvent.execute` returns `Mono<Event>` today. The ticket types + availability must reach the web layer without the domain depending on web DTOs (ArchUnit forbids it). Decision: introduce a small domain aggregate record `EventDetails` in `domain/model` holding the `Event`, its `List<TicketType>`, and the per-type availability (`InventoryCounter`); `GetEventUseCase` composes it from the existing `EventRepository.findTicketTypesByEventId` + `findInventoryCounter`; `ApiMapper` maps it to an enriched `EventResponse`. Rationale: keeps DTOs separate from domain (reuses `ApiMapper`), and `EventDetails` is a pure record with zero framework deps so ArchUnit stays green.

## Discrepancies between the brief and the actual code (resolved in favor of the code)

1. **Ticket constructor field order.** The brief lists `status, price, version, createdAt, ownerRef, reservationId, seatNumber`. The real record (`domain/model/.../ticket/Ticket.java`) is:
   `Ticket(UUID id, UUID eventId, UUID ticketTypeId, TicketStatus status, BigDecimal price, String seatNumber, String ownerRef, UUID reservationId, long version, LocalDateTime createdAt, LocalDateTime updatedAt)`.
   Note `seatNumber` precedes `ownerRef`/`reservationId`, `version` follows them, and there is an **11th field `updatedAt`** the brief omits. Construct eager tickets as:
   `new Ticket(UUID.randomUUID(), eventId, ticketTypeId, TicketStatus.AVAILABLE, price, null, null, null, 0L, now, now)` where `now = timeProvider.now()`. (`updatedAt = now` is consistent with how `TicketDynamoAdapter.toItem` persists it; `null` would also serialize cleanly, but `now` is cleaner for a freshly created row.)
2. **`createdAt` fix location.** The brief says "check CreateEventUseCase, EventDynamoAdapter.save, and the Event record." Root cause is the mapper/adapter interaction above; fix is in `CreateEventUseCase` (stamp via `TimeProvider`). No change needed to the `Event` record or `EventDynamoAdapter.save` (the adapter already persists `createdAt` when non-null via `formatDateTime`).
3. **GSI query shapes already in adapters.** `TicketDynamoAdapter` queries `eventId-ticketTypeId-status-index` with `keyConditionExpression("eventId = :eid AND ticketTypeId = :ttid")` and `status` as a **filter expression** — so the GSI key schema is hash `eventId` + range `ticketTypeId`, status NOT a key. It also queries `reservationId-index` (hash `reservationId`). `OrderDynamoAdapter` queries `idempotencyKey-index` (hash `idempotencyKey`). The init script creates none of these — matches DEFECT 2.

## Key facts for later curl/verification steps

- Routes (`RouterRest`, base `/api/v1`): `POST /events`, `GET /events/{eventId}`, `GET /events/{eventId}/availability?ticketTypeId=`, `POST /events/{eventId}/reservations`, `POST /orders`, `GET /orders/{orderId}`, `POST /tickets/{ticketId}/complimentary`.
- `CreateEventRequest` JSON fields: `name`, `description`, `date` (ISO LocalDateTime, e.g. `2025-12-01T20:00:00`), `venue`, `totalCapacity`, `ticketTypes` (array of `{name, price, totalQuantity}` — `TicketTypeRequest`).
- Current `EventResponse` fields: `id, name, description, date, venue, totalCapacity, createdAt`. After DEFECT 3 it also carries ticket types + availability (new field names defined in step 6).
- `AvailabilityResponse` fields: `eventId, ticketTypeId, availableCount, reservedCount, soldCount`.
- Tables: `panther-events`, `panther-tickets`, `panther-ticket-types`, `panther-inventory` (PK `pk` = `"<eventId>#<ticketTypeId>"`), `panther-reservations`, `panther-orders`, `panther-audit`.
- Build/run commands: compile `./gradlew compileJava`; run `./gradlew :app-service:bootRun` (host, port 8080); health `curl http://localhost:8080/actuator/health`.
- DynamoDB inspect: `AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy aws dynamodb scan --table-name panther-tickets --endpoint-url http://localhost:8000 --region us-east-1`.
- Infra network: `panther-ticketing_default`; init container form (preferred over compose):
  `podman run --rm --network panther-ticketing_default -e DYNAMODB_ENDPOINT=http://dynamodb-local:8000 -e SQS_ENDPOINT=http://sqs:9324 -e AWS_REGION=us-east-1 -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy -v ./local-infra:/init:ro --entrypoint /bin/sh amazon/aws-cli:latest /init/init-resources.sh`

## Ordering rationale

DEFECT 2 (step 1) is independent and must land before any runtime verification that queries the tickets GSIs, so it goes first. DEFECT 1 (steps 2–3) and the `createdAt` fix (step 4) all touch `CreateEventUseCase`/`UseCaseConfig`, so they are grouped and ordered by dependency (model/port facts → use case → wiring). DEFECT 3 enrichment (steps 5–7) builds on the `EventDetails` model and the now-populated tickets/counters. Each step leaves the code compiling.

---

- [ ] 1. **DEFECT 2 — Add the missing DynamoDB GSIs to the init script.** In `create_simple_table` the `panther-tickets` and `panther-orders` tables are created with PK `id` only. Replace the two `create_simple_table "panther-tickets"` and `create_simple_table "panther-orders"` calls with explicit `create-table` blocks (guarded by `table_exists`, matching the existing `panther-ticket-types` block style) that add GSIs, billing mode `PAY_PER_REQUEST`, `Projection={ProjectionType=ALL}`:
  - `panther-tickets`: attribute-definitions `id` (S), `eventId` (S), `ticketTypeId` (S), `reservationId` (S); key-schema hash `id`; GSI `eventId-ticketTypeId-status-index` = `KeySchema=[{AttributeName=eventId,KeyType=HASH},{AttributeName=ticketTypeId,KeyType=RANGE}]` (status is a filter, NOT a key); GSI `reservationId-index` = `KeySchema=[{AttributeName=reservationId,KeyType=HASH}]`.
  - `panther-orders`: attribute-definitions `id` (S), `idempotencyKey` (S); key-schema hash `id`; GSI `idempotencyKey-index` = `KeySchema=[{AttributeName=idempotencyKey,KeyType=HASH}]`.
  - Keep `panther-ticket-types` eventId-index as is; keep the script idempotent (the `table_exists` guard already provides this).
      Files: `local-infra/init-resources.sh`
      Verify: drop the affected tables then re-run the init container:
      `AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy aws dynamodb delete-table --table-name panther-tickets --endpoint-url http://localhost:8000 --region us-east-1` (repeat for `panther-orders`), then run the `podman run ... init-resources.sh` command above. Then
      `AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy aws dynamodb describe-table --table-name panther-tickets --endpoint-url http://localhost:8000 --region us-east-1` shows both `eventId-ticketTypeId-status-index` and `reservationId-index` under `GlobalSecondaryIndexes`, and `describe-table --table-name panther-orders` shows `idempotencyKey-index`.

- [ ] 2. **DEFECT 1 (prep) — Confirm the Ticket constructor and plan the eager-generation signature.** No code change beyond re-reading `domain/model/.../ticket/Ticket.java` to lock the 11-field order (see Discrepancy 1). The eager Ticket built in step 3 must be exactly:
  `new Ticket(UUID.randomUUID(), eventId, ticketTypeId, TicketStatus.AVAILABLE, price, null, null, null, 0L, now, now)`.
  This step exists only to anchor the exact shape; fold it into step 3 if preferred.
      Files: none (reference only).
      Verify: `./gradlew compileJava` still passes (no change).

- [ ] 3. **DEFECT 1 — Eagerly create individual Ticket records in `CreateEventUseCase`.** Add a `TicketRepository` field (constructor-injected) and a `TimeProvider`-stamped `now`. After `saveInventoryCounter(counter)` for each ticket type, build `savedTt.totalQuantity()` `Ticket` instances in `AVAILABLE` (price from `savedTt.price()`, exact constructor from step 2) and persist them via `ticketRepository.saveAll(List<Ticket>)`. Keep it reactive: generate the list with `IntStream.range(0, qty)` → `.mapToObj(...)` → `.toList()`, then `ticketRepository.saveAll(list).then(Mono.just(savedTt))` inside the existing per-ticket-type `flatMap`. For large quantities the adapter's `saveAll` already does `Flux.fromIterable(...).flatMap(this::save)` (bounded concurrency 256 by default) — correctness is satisfied; note batching as production hardening only. Do not touch `ReserveTicketsUseCase`/`ProcessOrderUseCase`/`ReleaseExpiredReservationsUseCase`.
      Files: `domain/use-case/src/main/java/com/panther/ticketing/usecase/CreateEventUseCase.java`
      Verify: `./gradlew compileJava` passes.

- [ ] 4. **DEFECT 1 wiring + DEFECT 3 `createdAt` — Update `CreateEventUseCase` constructor usage and stamp `createdAt`.** In `CreateEventUseCase.execute`, before `eventRepository.save(event)`, replace the incoming `event` with a copy whose `createdAt` is set when null: `Event stamped = event.createdAt() != null ? event : new Event(event.id(), event.name(), event.description(), event.date(), event.venue(), event.totalCapacity(), timeProvider.now());` and save `stamped`. Then update the `createEventUseCase` bean in `UseCaseConfig` to inject `TicketRepository`:
  change the bean method signature to `createEventUseCase(EventRepository eventRepository, TicketRepository ticketRepository, TimeProvider timeProvider)` and `return new CreateEventUseCase(eventRepository, ticketRepository, timeProvider);`. `TicketRepository` is already a Spring bean (`TicketDynamoAdapter`) and is injected into other beans, so it resolves.
      Files: `domain/use-case/src/main/java/com/panther/ticketing/usecase/CreateEventUseCase.java`, `applications/app-service/src/main/java/com/panther/ticketing/config/UseCaseConfig.java`
      Verify: `./gradlew compileJava` passes. Then end-to-end: start infra (step 1), `./gradlew :app-service:bootRun`, `POST /api/v1/events` with 1 ticket type `totalQuantity=5`; `aws dynamodb scan --table-name panther-tickets ...` returns 5 items all `status=AVAILABLE`; a subsequent `POST /api/v1/events/{eventId}/reservations` (quantity 2) returns 201 instead of a 409/ConflictException.

- [ ] 5. **DEFECT 3 (model) — Add an `EventDetails` domain aggregate.** Create a pure record in `domain/model` that carries the event plus its ticket types and their availability, with zero framework deps. Suggested shape:
  `public record EventDetails(Event event, List<TicketTypeAvailability> ticketTypes) {}` and a nested/sibling `public record TicketTypeAvailability(TicketType ticketType, InventoryCounter availability) {}` (both under `com.panther.ticketing.model.event`). Keeps ArchUnit green (model depends on nothing).
      Files: `domain/model/src/main/java/com/panther/ticketing/model/event/EventDetails.java` (and `TicketTypeAvailability.java`, or nest it)
      Verify: `./gradlew compileJava` passes.

- [ ] 6. **DEFECT 3 (use case + port) — Enrich `GetEventUseCase` to return `EventDetails`.** Change `IGetEvent.execute` to return `Mono<EventDetails>`. In `GetEventUseCase`, after `findById(eventId)` (keep the `NotFoundException` switchIfEmpty), use `eventRepository.findTicketTypesByEventId(eventId)` and for each `TicketType` call `findInventoryCounter(eventId, tt.id())` to build `TicketTypeAvailability`, collect to a list, and emit `EventDetails`. Compose reactively (`flatMap` + `Flux.flatMap` + `collectList`). Update the `getEventUseCase` bean signature in `UseCaseConfig` only if the return type change requires it (it does not — bean already returns `IGetEvent`).
      Files: `domain/ports/src/main/java/com/panther/ticketing/ports/in/IGetEvent.java`, `domain/use-case/src/main/java/com/panther/ticketing/usecase/GetEventUseCase.java`
      Verify: `./gradlew compileJava` passes (expect a compile break in `ApiHandler`/`ApiMapper` until step 7 — do steps 6 and 7 together before compiling if preferred).

- [ ] 7. **DEFECT 3 (web) — Enrich `EventResponse`, `ApiMapper`, and the handler.** Extend `EventResponse` with a `ticketTypes` list field. Add a nested response DTO (keep DTOs separate from domain), e.g.:
  `record TicketTypeAvailabilityResponse(UUID id, String name, BigDecimal price, int totalQuantity, int availableCount, int reservedCount, int soldCount)` and add `List<TicketTypeAvailabilityResponse> ticketTypes` to `EventResponse` (append as the last field so existing fields `id,name,description,date,venue,totalCapacity,createdAt` keep their positions). Add `ApiMapper.toEventResponse(EventDetails)` building the enriched response (map `id/name/price/totalQuantity` from `TicketType`, `availableCount/reservedCount/soldCount` from `InventoryCounter`); keep the existing `toEventResponse(Event)` for `createEvent` which still returns a bare `Event` (its ticketTypes list will be empty/null — acceptable, create returns the event only). Update `ApiHandler.getEvent` to `.map(ApiMapper::toEventResponse)` against the `EventDetails` overload.
      Files: `infrastructure/entry-points/reactive-web/src/main/java/com/panther/ticketing/api/dto/EventResponse.java`, new `.../api/dto/TicketTypeAvailabilityResponse.java`, `.../api/mapper/ApiMapper.java`, `.../api/handlers/ApiHandler.java`
      Verify: `./gradlew compileJava` passes. End-to-end: `GET /api/v1/events/{eventId}` returns `createdAt` non-null AND a `ticketTypes` array where each element has `id,name,price,totalQuantity,availableCount,reservedCount,soldCount` reflecting the counter (e.g. `availableCount=5` right after creation of a 5-qty type).

- [ ] 8. **Audit preservation check.** Confirm no audit calls were removed in steps 3–7 (`AuditGateway.record` usages in `ReserveTicketsUseCase`, `ProcessOrderUseCase`, `MarkComplimentaryUseCase`, `ReleaseExpiredReservationsUseCase` remain intact). The spec requires auditable state transitions; this task adds no new transition that needs auditing (ticket pre-generation is initial creation, not a transition), so no new audit calls are required.
      Files: none (review-only).
      Verify: `grep -R "auditGateway.record" domain/use-case/src/main/java` shows the same call sites as before the task (unchanged count).

- [ ] 9. **README — Document the eager-ticket-generation decision.** Add to "Key Architectural Decisions" a point stating individual `Ticket` records are pre-created in `AVAILABLE` when the event/ticketType is created, so all five ticket states are realized on real records and reservation expiry is a literal `RESERVED -> AVAILABLE` transition; note that for very large capacities a batched or lazy generation strategy would be used in production. Keep the professional, emoji-free, technical tone of the existing README.
      Files: `README.md`
      Verify: the "Key Architectural Decisions" section contains the eager-generation point and the large-capacity production note.

- [ ] 10. **Full build + end-to-end regression.** Run the full compile and exercise the happy path once more end-to-end to confirm the three defects are fixed together.
      Files: none.
      Verify: `./gradlew compileJava` passes. With infra up and the app running: (a) `POST /api/v1/events` (ticket type qty 5) → 201; (b) `scan panther-tickets` → 5 AVAILABLE rows; (c) `GET /api/v1/events/{eventId}` → non-null `createdAt` + populated `ticketTypes` availability; (d) `GET /api/v1/events/{eventId}/availability?ticketTypeId=...` → 200 (no "table does not have the specified index" error); (e) `POST /api/v1/events/{eventId}/reservations` qty 2 → 201 and availability now shows `availableCount=3, reservedCount=2`.

## Notes / assumptions

- `IGetEvent` return-type change (step 6) is a source-compatible internal change; the only caller is `ApiHandler.getEvent`, updated in step 7. No other module references `IGetEvent.execute`.
- Appending `ticketTypes` as the last `EventResponse` field preserves JSON field names of existing fields for any pre-written curl/Postman collections.
- Do NOT add unit tests in this task (explicitly deferred to a later phase per the brief).
- The implement-and-review loop (reviewer writes `/.agents/tasks/panther-defect-fixes/review.json`, `verdict` → `APPROVED`) remains the executor of this plan; this plan file is its input.
