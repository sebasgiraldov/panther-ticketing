# Panther Ticketing — Guía de Infraestructura Local

Documento de operación para el aprovisionamiento y arranque del entorno local del
sistema Panther Ticketing. Describe la topología de servicios, la secuencia de
arranque, los mecanismos de aprovisionamiento y los procedimientos de verificación,
diagnóstico y apagado.

---

## Tabla de contenido

1. [Arquitectura del entorno local](#1-arquitectura-del-entorno-local)
2. [Requisitos del entorno](#2-requisitos-del-entorno)
3. [Instalación del runtime de contenedores](#3-instalación-del-runtime-de-contenedores)
4. [Secuencia de arranque](#4-secuencia-de-arranque)
5. [Procedimiento detallado](#5-procedimiento-detallado)
6. [Verificación del entorno](#6-verificación-del-entorno)
7. [Apagado y limpieza](#7-apagado-y-limpieza)
8. [Diagnóstico de incidencias](#8-diagnóstico-de-incidencias)
9. [Referencia de comandos](#9-referencia-de-comandos)

---

## 1. Arquitectura del entorno local

El entorno se compone de cinco servicios orquestados mediante `docker-compose`,
ejecutados sobre el runtime de contenedores Podman.

| Servicio | Responsabilidad | Imagen | Puerto |
|----------|-----------------|--------|--------|
| `dynamodb-local` | Persistencia NoSQL (eventos, tipos de entrada, inventario, tickets, reservas, órdenes, auditoría) | `amazon/dynamodb-local` | `8000` |
| `sqs` | Broker de mensajería compatible con la API de Amazon SQS | `softwaremill/elasticmq-native` | `9324` |
| `init-resources` | Aprovisionamiento de tablas DynamoDB (job efímero, idempotente) | `amazon/aws-cli` | — |
| `dynamodb-admin` | Consola web de inspección para DynamoDB Local | `aaronshaf/dynamodb-admin` | `8001` |
| `app` | Servicio reactivo (Spring Boot 4 / WebFlux) | build local (`Dockerfile`) | `8080` |

### Decisiones de infraestructura

- **DynamoDB Local** se ejecuta con la opción `-inMemory`, por lo que el estado es
  volátil y se regenera en cada arranque, garantizando un entorno determinista y
  reproducible entre ejecuciones.
- **ElasticMQ** sustituye a LocalStack como emulador de SQS. Implementa la API de
  SQS sin dependencias de licenciamiento ni token de autenticación de pago, con una
  huella de recursos significativamente menor al acotarse exclusivamente al servicio
  de colas requerido por el dominio. El cliente `SqsAsyncClient` de la aplicación
  opera contra ElasticMQ sin modificaciones de código; únicamente cambia el endpoint.
- **El aplicativo no aprovisiona su propia infraestructura.** La creación de tablas
  se delega a un contenedor de inicialización de un solo uso. Esto reproduce
  localmente la separación entre el plano de control (IaC / Terraform en producción)
  y el plano de datos del runtime, cuyo rol de ejecución debe restringirse a
  operaciones de datos (`GetItem`, `PutItem`, `UpdateItem`, `Query`, `SendMessage`,
  `ReceiveMessage`, `DeleteMessage`) sin privilegios de creación de recursos. El job
  `init-resources` es el equivalente local de la IaC.

### Secuencia de dependencias

```
┌──────────────────┐      ┌──────────┐      ┌──────────────────┐      ┌────────┐
│  dynamodb-local  │ ───► │          │ ───► │                  │ ───► │        │
│       +          │      │ healthy  │      │  init-resources  │      │  app   │
│  sqs (ElasticMQ) │ ───► │          │      │  (provisioning)  │      │        │
└──────────────────┘      └──────────┘      └──────────────────┘      └────────┘
   arranque inicial        healthchecks       crea las 7 tablas         arranque
                                               y finaliza                 final
```

El orden se garantiza mediante condiciones `depends_on` (`service_healthy` y
`service_completed_successfully`) declaradas en `docker-compose.yml`.

---

## 2. Requisitos del entorno

| Requisito | Versión | Verificación |
|-----------|---------|--------------|
| Sistema operativo | macOS (Apple Silicon / `arm64`) | `uname -m` |
| Homebrew | cualquiera reciente | `brew --version` |
| Java | 25 (LTS) | `java -version` |

El runtime de contenedores (Podman) se instala en la sección siguiente.

---

## 3. Instalación del runtime de contenedores

Se utiliza Podman como runtime de contenedores, compatible con las especificaciones
`docker-compose` y con las imágenes OCI empleadas por el proyecto.

### 3.1 Instalación de binarios

```bash
brew install podman podman-compose
```

### 3.2 Provisión de la máquina virtual

En macOS la ejecución de contenedores Linux requiere un kernel Linux, provisto por
una máquina virtual gestionada por Podman. Se inicializa una única vez:

```bash
podman machine init
podman machine start
```

La máquina virtual se detiene al reiniciar el host. Para reanudarla:

```bash
podman machine start
```

Un mensaje `already running` indica que la máquina ya se encuentra activa.

### 3.3 Validación del runtime

```bash
podman run --rm hello-world
```

### 3.4 AWS CLI (opcional)

Requerido únicamente para inspección manual de tablas y colas desde el host:

```bash
brew install awscli
```

---

## 4. Secuencia de arranque

Procedimiento abreviado para entornos ya configurados:

```bash
# 1. Asegurar la máquina virtual de Podman
podman machine start

# 2. Situarse en la raíz del proyecto
cd /ruta/al/repositorio/panther-ticketing

# 3. Levantar la infraestructura (persistencia + mensajería) en segundo plano
podman compose up -d dynamodb-local sqs

# 4. Ejecutar el aprovisionamiento de tablas
podman compose up init-resources

# 5. (Opcional) Levantar la consola de inspección de DynamoDB
podman compose up -d dynamodb-admin

# 6. Arrancar el servicio (cargando antes las variables de entorno)
set -a && . ./.env && set +a && ./gradlew :app-service:bootRun
```

Endpoint del servicio: `http://localhost:8080`

El detalle de la carga de `.env` y de los dos modos de ejecución del servicio se
documenta en `docs/PUESTA_EN_MARCHA.md`.

---

## 5. Procedimiento detallado

### Paso 1 — Máquina virtual de Podman

```bash
podman machine start
```

Inicializa el runtime responsable de la ejecución de contenedores. Resultado
esperado: `Machine "podman-machine-default" started successfully`.

### Paso 2 — Directorio de trabajo

```bash
cd /ruta/al/repositorio/panther-ticketing
```

Todos los comandos subsiguientes se ejecutan desde la raíz del proyecto, donde
reside `docker-compose.yml`.

### Paso 3 — Infraestructura de persistencia y mensajería

```bash
podman compose up -d dynamodb-local sqs
```

Descarga las imágenes requeridas (únicamente en la primera ejecución), e inicia
DynamoDB Local (`8000`) y ElasticMQ (`9324`) en modo *detached*.

Estado esperado tras los healthchecks:

```
NAMES              STATUS
panther-dynamodb   Up 10 seconds (healthy)
panther-sqs        Up 10 seconds (healthy)
```

### Paso 4 — Aprovisionamiento de tablas

```bash
podman compose up init-resources
```

Ejecuta `deploy/local/init-resources.sh` dentro de un contenedor efímero que crea las
siete tablas de DynamoDB y verifica la disponibilidad de la cola. El script es
**idempotente**: en ejecuciones posteriores omite los recursos existentes
(`[skip]` en lugar de `[create]`).

Salida esperada:

```
==> Bootstrapping local infrastructure
    [create] table panther-events (PK: id)
    [create] table panther-tickets (PK: id)
    [create] table panther-reservations (PK: id)
    [create] table panther-orders (PK: id)
    [create] table panther-audit (PK: id)
    [create] table panther-ticket-types (PK: id, GSI: eventId-index)
    [create] table panther-inventory (PK: pk)
==> Verifying SQS queue is reachable
    [ok] queue panther-orders is available
==> Local infrastructure ready
```

> Las colas (`panther-orders` y su DLQ `panther-orders-dlq` con política de redrive)
> se declaran de forma estática en `deploy/local/elasticmq.conf` y las crea ElasticMQ
> durante su arranque. El job de inicialización únicamente verifica su disponibilidad.

### Paso 5 — Consola de inspección (opcional)

```bash
podman compose up -d dynamodb-admin
```

Levanta la consola web de DynamoDB Local en `http://localhost:8001`.

### Paso 6 — Arranque del servicio

El servicio puede ejecutarse en el host (recomendado para desarrollo) o de forma
contenerizada. Ambos modos, junto con la obligatoriedad de cargar `.env` en el modo
host, se detallan en `docs/PUESTA_EN_MARCHA.md`.

**Opción A — Ejecución en el host**

```bash
set -a && . ./.env && set +a && ./gradlew :app-service:bootRun
```

El servicio se ejecuta en el host y se conecta a los contenedores de
infraestructura mediante los endpoints definidos en `.env`.

**Opción B — Ejecución contenerizada**

```bash
podman compose up --build
```

Construye la imagen del servicio a partir del `Dockerfile` y la orquesta junto con
el resto de componentes. La condición `service_completed_successfully` sobre
`init-resources` garantiza que el servicio arranque con el esquema ya aprovisionado.

Traza de arranque esperada:

```
:: Spring Boot ::  4.1.0-RC1
:: Java        ::  25.0.x
Netty started on port 8080 (http)
Started MainApplication in ~1.2 seconds
```

---

## 6. Verificación del entorno

### Estado de salud del servicio

```bash
curl http://localhost:8080/actuator/health
```

Respuesta esperada: `{"status":"UP"}`.

### Inventario de tablas DynamoDB

```bash
aws dynamodb list-tables \
  --endpoint-url http://localhost:8000 \
  --region us-east-1
```

Se esperan las siete tablas del dominio (`panther-events`, `panther-tickets`,
`panther-reservations`, `panther-orders`, `panther-audit`, `panther-ticket-types`,
`panther-inventory`).

### Inventario de colas SQS

```bash
aws sqs list-queues \
  --endpoint-url http://localhost:9324 \
  --region us-east-1
```

Se esperan `panther-orders` y `panther-orders-dlq`.

> En ausencia de credenciales AWS configuradas en el host, antepóngalas de forma
> explícita: `AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy aws ...`.

### Inspección visual de datos (DynamoDB Admin)

El servicio `dynamodb-admin` expone una consola web para DynamoDB Local:

```
http://localhost:8001
```

Permite listar las tablas, inspeccionar ítems y filtrar por atributos — por ejemplo,
consultar la tabla `panther-tickets` y observar el estado (`AVAILABLE`, `RESERVED`,
`PENDING_CONFIRMATION`, `SOLD`, `COMPLIMENTARY`) de cada entrada individual.

### Inspección de colas (profundidad de mensajes)

La imagen de ElasticMQ utilizada no incluye consola web; la inspección de colas se
realiza por API. El script `deploy/local/queue-status.sh` resume cada cola y sus
contadores de mensajes:

```bash
sh deploy/local/queue-status.sh
```

Salida de ejemplo:

```
Queue: panther-orders-dlq           available=0 in-flight=0
Queue: panther-orders               available=0 in-flight=0
```

Dado que el consumidor procesa las órdenes en ~1 s, el valor `available` suele
observarse en 0 en estado estable (los mensajes se consumen de inmediato).

---

## 7. Apagado y limpieza

### Detención del servicio

Si se ejecutó mediante `bootRun`, interrumpa con `Ctrl + C`.

### Baja de contenedores

```bash
podman compose down
```

Detiene y elimina los contenedores. Dado el modo `-inMemory` de DynamoDB, el estado
se descarta y se regenera en el siguiente arranque.

### Detención de la máquina virtual (opcional)

```bash
podman machine stop
```

---

## 8. Diagnóstico de incidencias

### `podman compose up` no retorna el control de la terminal

La ejecución sin `-d` opera en primer plano, transmitiendo la salida de los
contenedores. Utilice el modo *detached* para la infraestructura:

```bash
podman compose up -d dynamodb-local sqs
```

Para `init-resources` (job que debe finalizar), si la orquestación no retorna el
control, ejecútelo como contenedor independiente:

```bash
podman run --rm \
  --network panther-ticketing_default \
  -e DYNAMODB_ENDPOINT=http://dynamodb-local:8000 \
  -e SQS_ENDPOINT=http://sqs:9324 \
  -e AWS_REGION=us-east-1 \
  -e AWS_ACCESS_KEY_ID=dummy \
  -e AWS_SECRET_ACCESS_KEY=dummy \
  -v ./deploy/local:/init:ro \
  --entrypoint /bin/sh \
  amazon/aws-cli:latest /init/init-resources.sh
```

### `Error: unable to start ... already running`

La máquina virtual de Podman ya se encuentra activa. No requiere acción.

### El servicio reporta `Connection refused` a `localhost:8000` o `localhost:9324`

La infraestructura no está operativa. Confirme mediante `podman ps` que
`panther-dynamodb` y `panther-sqs` figuran como `(healthy)`; de lo contrario,
reejecute el [Paso 3](#paso-3--infraestructura-de-persistencia-y-mensajería).

### Latencia elevada en la descarga de imágenes

Esperable en la primera ejecución. Las imágenes quedan en caché local para
arranques posteriores.

### Puerto en uso (`8080`, `8000`, `9324`, `8001`)

Identifique el proceso que retiene el puerto:

```bash
lsof -i :8080
```

---

## 9. Referencia de comandos

| Operación | Comando |
|-----------|---------|
| Iniciar máquina virtual | `podman machine start` |
| Detener máquina virtual | `podman machine stop` |
| Levantar infraestructura | `podman compose up -d dynamodb-local sqs` |
| Aprovisionar tablas | `podman compose up init-resources` |
| Consola DynamoDB (web) | `podman compose up -d dynamodb-admin` → http://localhost:8001 |
| Estado de colas | `sh deploy/local/queue-status.sh` |
| Listar contenedores | `podman ps` |
| Inspeccionar logs | `podman logs <nombre-contenedor>` |
| Arranque del servicio (host) | `set -a && . ./.env && set +a && ./gradlew :app-service:bootRun` |
| Arranque contenerizado | `podman compose up --build` |
| Estado de salud | `curl http://localhost:8080/actuator/health` |
| Baja del entorno | `podman compose down` |

---

Panther Ticketing — Sistema reactivo de procesamiento de eventos de ticketing
Java 25 · Spring Boot 4 · Spring WebFlux · DynamoDB · SQS (ElasticMQ) · Clean Architecture
