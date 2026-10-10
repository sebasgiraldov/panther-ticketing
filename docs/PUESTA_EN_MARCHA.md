# Panther Ticketing — Guía de Puesta en Marcha

Guía de arranque de extremo a extremo del sistema Panther Ticketing: desde los
requisitos y la compilación hasta la ejecución del servicio y la validación
funcional completa del flujo de negocio (creación de evento, reserva, compra
asíncrona y cortesía).

Para el detalle de la topología de contenedores, el aprovisionamiento y el
diagnóstico de infraestructura, consulte `docs/INFRAESTRUCTURA.md`.

---

## Tabla de contenido

1. [Requisitos previos](#1-requisitos-previos)
2. [Clonado y compilación](#2-clonado-y-compilación)
3. [Modos de ejecución del servicio](#3-modos-de-ejecución-del-servicio)
4. [Validación funcional de extremo a extremo](#4-validación-funcional-de-extremo-a-extremo)
5. [Flujo alterno: cortesía](#5-flujo-alterno-cortesía)
6. [Transiciones de los contadores de disponibilidad](#6-transiciones-de-los-contadores-de-disponibilidad)
7. [Colección de solicitudes (Postman)](#7-colección-de-solicitudes-postman)
8. [Inspección de datos y colas](#8-inspección-de-datos-y-colas)

---

## 1. Requisitos previos

| Requisito | Versión | Verificación |
|-----------|---------|--------------|
| Sistema operativo | macOS (Apple Silicon / `arm64`) | `uname -m` |
| Homebrew | cualquiera reciente | `brew --version` |
| Java | 25 (LTS) | `java -version` |
| Podman | cualquiera reciente | `podman --version` |

La instalación de Podman y la provisión de su máquina virtual se documentan en
`docs/INFRAESTRUCTURA.md`. La infraestructura (DynamoDB Local, ElasticMQ,
dynamodb-admin) debe estar operativa antes de arrancar el servicio.

---

## 2. Clonado y compilación

```bash
git clone <url-del-repositorio> panther-ticketing
cd panther-ticketing
./gradlew build
```

El proyecto es un build multi-módulo de Gradle (arquitectura limpia / hexagonal). El
comando `./gradlew build` compila todos los módulos y ejecuta las verificaciones. Si
solo se requiere compilar, utilice `./gradlew compileJava`.

---

## 3. Modos de ejecución del servicio

El servicio admite dos modos de ejecución. En ambos la infraestructura corre en
contenedores; lo que cambia es dónde se ejecuta el aplicativo.

### Modo A — Totalmente contenerizado

```bash
podman compose up --build
```

Construye la imagen del servicio y la orquesta junto con la infraestructura.
`docker-compose`/`podman-compose` carga automáticamente el archivo `.env` mediante la
directiva `env_file`, por lo que no se requiere ningún paso adicional de carga de
variables.

### Modo B — Infraestructura en contenedores + servicio en el host

Recomendado para desarrollo (iteración rápida, depuración). Requiere **cargar
previamente las variables de entorno**:

```bash
set -a && . ./.env && set +a && ./gradlew :app-service:bootRun
```

### Por qué es obligatorio cargar `.env` en el modo B

El aplicativo **no define valores por defecto** para su configuración:

- `application.yml` referencia las propiedades mediante `${ENV_VAR}` **sin valores de
  respaldo** (por ejemplo `${DYNAMODB_ENDPOINT}`, `${SQS_ENDPOINT}`, `${AWS_REGION}`,
  `${SERVER_PORT}`).
- Las clases de configuración `DynamoDbConfig` y `SqsConfig` inyectan sus parámetros
  con campos `@Value` **sin valores por defecto**.

A diferencia de `podman compose` (que lee `.env` vía `env_file`), `./gradlew
:app-service:bootRun` **no lee** el archivo `.env`. En consecuencia, ejecutar
`bootRun` sin cargar `.env` falla durante el arranque por propiedades ausentes
(`Could not resolve placeholder ...`).

El prefijo `set -a && . ./.env && set +a` exporta todas las variables definidas en
`.env` al entorno del proceso antes de invocar Gradle, resolviendo así todos los
marcadores `${ENV_VAR}`.

---

## 4. Validación funcional de extremo a extremo

Flujo de negocio completo: creación de evento, consulta de disponibilidad, reserva
temporal, compra asíncrona y consulta de estado de la orden. Sustituya los
marcadores `{...}` por los identificadores devueltos en cada respuesta.

### 4.1 Crear evento — `POST /api/v1/events`

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Concierto Panther",
    "description": "Show en vivo",
    "date": "2026-12-01T20:00:00",
    "venue": "Estadio Manizales",
    "totalCapacity": 10,
    "ticketTypes": [
      { "name": "General", "price": 50000, "totalQuantity": 10 }
    ]
  }'
```

Respuesta (`201 Created`):

```json
{
  "id": "{eventId}",
  "name": "Concierto Panther",
  "description": "Show en vivo",
  "date": "2026-12-01T20:00:00",
  "venue": "Estadio Manizales",
  "totalCapacity": 10,
  "createdAt": "2026-10-10T10:00:00",
  "ticketTypes": [
    {
      "id": "{ticketTypeId}",
      "name": "General",
      "price": 50000,
      "totalQuantity": 10,
      "availableCount": 10,
      "reservedCount": 0,
      "soldCount": 0
    }
  ]
}
```

Registre `eventId` y `ticketTypeId`.

### 4.2 Obtener evento — `GET /api/v1/events/{eventId}`

```bash
curl http://localhost:8080/api/v1/events/{eventId}
```

Devuelve el evento con su arreglo `ticketTypes` y los contadores de disponibilidad
por tipo de entrada (misma estructura que la respuesta de creación).

### 4.3 Consultar disponibilidad — `GET /api/v1/events/{eventId}/availability`

```bash
curl "http://localhost:8080/api/v1/events/{eventId}/availability?ticketTypeId={ticketTypeId}"
```

Respuesta (`200 OK`):

```json
{
  "eventId": "{eventId}",
  "ticketTypeId": "{ticketTypeId}",
  "availableCount": 10,
  "reservedCount": 0,
  "soldCount": 0
}
```

### 4.4 Reservar entradas — `POST /api/v1/events/{eventId}/reservations`

Reserva temporal con retención de 10 minutos.

```bash
curl -X POST http://localhost:8080/api/v1/events/{eventId}/reservations \
  -H "Content-Type: application/json" \
  -d '{
    "ticketTypeId": "{ticketTypeId}",
    "quantity": 2,
    "customerRef": "cliente-123"
  }'
```

Respuesta (`201 Created`):

```json
{
  "id": "{reservationId}",
  "eventId": "{eventId}",
  "ticketTypeId": "{ticketTypeId}",
  "ticketIds": ["...", "..."],
  "customerRef": "cliente-123",
  "expiresAt": "2026-10-10T10:10:00",
  "status": "ACTIVE"
}
```

Registre `reservationId`.

### 4.5 Comprar — `POST /api/v1/orders`

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "reservationId": "{reservationId}",
    "customerRef": "cliente-123"
  }'
```

La operación encola la orden y responde de inmediato con `202 Accepted`. El
procesamiento (confirmación, cálculo del importe y marcado de las entradas como
vendidas) se ejecuta de forma asíncrona en el consumidor.

Respuesta (`202 Accepted`):

```json
{
  "id": "{orderId}",
  "reservationId": "{reservationId}",
  "customerRef": "cliente-123",
  "quantity": 2,
  "totalAmount": 0,
  "status": "PENDING",
  "createdAt": "2026-10-10T10:01:00",
  "updatedAt": "2026-10-10T10:01:00"
}
```

> El `totalAmount` se devuelve en `0` en la respuesta inmediata porque la orden aún no
> ha sido procesada. El importe definitivo se calcula durante el procesamiento
> asíncrono y se refleja al consultar el estado de la orden.

### 4.6 Consultar estado de la orden — `GET /api/v1/orders/{orderId}`

```bash
curl http://localhost:8080/api/v1/orders/{orderId}
```

Consulte de forma repetida (*polling*) hasta observar `status: CONFIRMED`. El estado
transita de `PENDING`/`PROCESSING` a `CONFIRMED` una vez procesada la orden, momento
en el cual `totalAmount` refleja el importe real:

```json
{
  "id": "{orderId}",
  "reservationId": "{reservationId}",
  "customerRef": "cliente-123",
  "quantity": 2,
  "totalAmount": 100000,
  "status": "CONFIRMED",
  "createdAt": "2026-10-10T10:01:00",
  "updatedAt": "2026-10-10T10:01:01"
}
```

### 4.7 Consultar disponibilidad tras la venta

```bash
curl "http://localhost:8080/api/v1/events/{eventId}/availability?ticketTypeId={ticketTypeId}"
```

Tras confirmarse la orden, las dos entradas pasan de reservadas a vendidas:

```json
{
  "eventId": "{eventId}",
  "ticketTypeId": "{ticketTypeId}",
  "availableCount": 8,
  "reservedCount": 0,
  "soldCount": 2
}
```

---

## 5. Flujo alterno: cortesía

Una entrada de cortesía (`COMPLIMENTARY`) atraviesa el **mismo flujo de reserva** que
una venta, pero en lugar de pagar se marca la reserva como cortesía. El endpoint es:

```
POST /api/v1/events/{eventId}/complimentary
```

con cuerpo `{ "reservationId": "...", "ownerRef": "..." }`.

La operación **consume su propia reserva ACTIVA**. Por tanto requiere una reserva
nueva y vigente: no puede aplicarse sobre una reserva ya comprada ni expirada. Si la
reserva no está ACTIVA, la API responde `400 Bad Request`.

### 5.1 Crear una reserva nueva (independiente de la compra anterior)

```bash
curl -X POST http://localhost:8080/api/v1/events/{eventId}/reservations \
  -H "Content-Type: application/json" \
  -d '{
    "ticketTypeId": "{ticketTypeId}",
    "quantity": 2,
    "customerRef": "invitado-vip"
  }'
```

Registre el `reservationId` devuelto (en adelante `{reservationIdCortesia}`).

### 5.2 Marcar la reserva como cortesía — `POST /api/v1/events/{eventId}/complimentary`

```bash
curl -X POST http://localhost:8080/api/v1/events/{eventId}/complimentary \
  -H "Content-Type: application/json" \
  -d '{
    "reservationId": "{reservationIdCortesia}",
    "ownerRef": "invitado-vip"
  }'
```

Respuesta (`200 OK`): la reserva pasa a estado de cortesía y sus entradas se marcan
como `COMPLIMENTARY`. Esta transición **no constituye una venta**: libera el conteo de
reservadas sin incrementar el de vendidas.

> Nota: el endpoint es por reserva, no por ticket individual. El intento de marcar
> como cortesía una entrada en estado `SOLD` (es decir, una reserva ya comprada) es
> inválido y responde `400 Bad Request`, lo cual es el comportamiento esperado:
> `SOLD` es un estado final e irreversible.

---

## 6. Transiciones de los contadores de disponibilidad

Los contadores `availableCount` / `reservedCount` / `soldCount` evolucionan de forma
determinista a lo largo del flujo. Partiendo de un evento con capacidad 10:

| Operación | available | reserved | sold |
|-----------|-----------|----------|------|
| Crear evento (capacidad 10) | 10 | 0 | 0 |
| Reservar 2 | 8 | 2 | 0 |
| Comprar (orden confirmada) | 8 | 0 | 2 |

Para el flujo de cortesía, partiendo de una reserva nueva de 2 entradas:

| Operación | available | reserved | sold |
|-----------|-----------|----------|------|
| Reservar 2 (cortesía) | 6 | 2 | 0 |
| Marcar como cortesía | 6 | 0 | 0 |

Obsérvese que la cortesía **decrementa `reservedCount` sin tocar `soldCount`**: por
eso no se contabiliza como una venta. Los valores de `available` del segundo cuadro
continúan desde el estado dejado por la compra de la sección 4 (10 - 2 vendidas - 2
reservadas de cortesía = 6).

---

## 7. Colección de solicitudes (Postman)

El repositorio incluye una colección lista para importar en
`docs/postman/panther-ticketing.postman_collection.json`. Encadena automáticamente
los identificadores entre solicitudes mediante scripts *post-response*, de modo que
el flujo completo puede ejecutarse de principio a fin —manualmente o con el
Collection Runner— sin copiar identificadores a mano.

Variables de colección encadenadas:

- `event_id` — capturada al crear el evento.
- `ticket_type_id` — capturada al crear el evento.
- `reservation_id` — capturada al reservar.
- `order_id` — capturada al comprar.

Importación: en Postman, **Import → File** y seleccione el archivo de la colección. La
variable `base_url` apunta por defecto a `http://localhost:8080`.

---

## 8. Inspección de datos y colas

### Inspección visual de datos (DynamoDB Admin)

La consola web de DynamoDB Local permite listar tablas e inspeccionar ítems:

```
http://localhost:8001
```

Resulta útil para observar el estado individual de cada entrada en la tabla
`panther-tickets` (`AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`,
`COMPLIMENTARY`).

### Inspección de colas

El estado y la profundidad de las colas se consultan mediante:

```bash
sh deploy/local/queue-status.sh
```

Dado que el consumidor procesa las órdenes en ~1 s, el contador `available` suele
observarse en 0 en estado estable.

---

Panther Ticketing — Sistema reactivo de procesamiento de eventos de ticketing
Java 25 · Spring Boot 4 · Spring WebFlux · DynamoDB · SQS (ElasticMQ) · Clean Architecture
