# Panther Ticketing — Infraestructura Local

Cómo levantar la infraestructura necesaria para correr el sistema.

## Servicios

| Servicio | Función | Imagen | Puerto |
|----------|---------|--------|--------|
| `dynamodb-local` | Base de datos NoSQL | `amazon/dynamodb-local` | `8000` |
| `sqs` | Cola de mensajes (API de Amazon SQS vía ElasticMQ) | `softwaremill/elasticmq-native` | `9324` |
| `init-resources` | Crea las tablas de DynamoDB (job de un solo uso) | `amazon/aws-cli` | — |
| `dynamodb-admin` | Consola web para inspeccionar DynamoDB | `aaronshaf/dynamodb-admin` | `8001` |
| `app` | El servicio (Spring Boot 4 / WebFlux) | build local | `8080` |

Todo corre en contenedores sobre Podman. DynamoDB usa `-inMemory`, por lo que los
datos se reinician en cada arranque.

## Requisitos

- Podman

## Instalar Podman

**macOS**
```bash
brew install podman
podman machine init
podman machine start
```

**Windows**
```powershell
winget install RedHat.Podman
podman machine init
podman machine start
```

La máquina virtual se detiene al reiniciar el equipo; reanúdela con `podman machine start`.

## Levantar la infraestructura

Desde la raíz del repositorio:

```bash
# 0. Arrancar la máquina virtual de Podman (se detiene al reiniciar el equipo)
podman machine start

# 1. Base de datos y cola
podman compose up -d dynamodb-local sqs

# 2. Crear las tablas
podman compose up init-resources

# 3. (Opcional) consola web de DynamoDB
podman compose up -d dynamodb-admin
```

> En Windows (PowerShell) los comandos `podman` son idénticos.

Verifique que los contenedores estén `healthy`:

```bash
podman ps
```

> Para levantar la infraestructura **y** el servicio en un solo paso, use
> `podman compose up --build` (ver "Modo A" en `docs/PUESTA_EN_MARCHA.md`).

## Verificar

- Consola web de DynamoDB: `http://localhost:8001`
- Estado de las colas: `sh deploy/local/queue-status.sh`

## Apagar

```bash
podman compose down
```

## Problemas comunes

- **`podman compose up` no devuelve la terminal**: córrelo con `-d` para que quede en segundo plano.
- **`already running`**: la máquina virtual de Podman ya está activa, no requiere acción.
- **`Connection refused` a `localhost:8000` o `localhost:9324`**: la infraestructura no está arriba; verifique con `podman ps` que los contenedores estén `healthy`.
- **Puerto en uso**: identifique el proceso con `lsof -i :8080` (macOS/Linux) o `netstat -ano | findstr :8080` (Windows).
