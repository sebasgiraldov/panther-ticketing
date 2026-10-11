# Panther Ticketing — Puesta en Marcha

Cómo compilar y ejecutar el sistema.

## Requisitos

- Java 25 (LTS)
- Podman (runtime de contenedores)
- Git

## Compilar

**macOS / Linux**
```bash
git clone <url-del-repositorio> panther-ticketing
cd panther-ticketing
./gradlew build
```

**Windows (PowerShell)**
```powershell
git clone <url-del-repositorio> panther-ticketing
cd panther-ticketing
.\gradlew.bat build
```

## Ejecutar

Elija **uno** de los dos modos. No ejecute ambos a la vez: los dos usan el puerto
`8080` y entrarían en conflicto.

En ambos casos, primero arranque la máquina virtual de Podman (necesaria en macOS y
Windows; se detiene al reiniciar el equipo):

```bash
podman machine start
```

Un mensaje `already running` indica que ya está activa.

### Modo A — Todo en contenedores (recomendado para probar)

Un solo comando levanta la infraestructura y el servicio juntos (base de datos, cola,
creación de tablas y la aplicación). El archivo `.env` se carga automáticamente.

```bash
podman compose up --build
```

Los comandos `podman` son idénticos en macOS, Linux y Windows (PowerShell).

Para detener y limpiar todo:

```bash
podman compose down
```

> DynamoDB Local corre `-inMemory` — los datos se pierden al hacer `down`. El próximo `up` arranca con tablas vacías.

### Modo B — Servicio en el host (recomendado para desarrollo)

La infraestructura corre en contenedores y el servicio en la máquina, para iterar
sobre el código sin reconstruir la imagen.

**macOS / Linux**
```bash
podman compose up -d dynamodb-local sqs dynamodb-admin init-resources
./gradlew :app-service:bootRun
```

**Windows (PowerShell)**
```powershell
podman compose up -d dynamodb-local sqs dynamodb-admin init-resources
.\gradlew.bat :app-service:bootRun
```

Para detener la infraestructura:

```bash
podman compose down
```

## Verificar

```bash
curl http://localhost:8080/actuator/health
```

Respuesta esperada: `{"status":"UP"}`.

## Colección de solicitudes

El repositorio incluye una colección Postman en `docs/postman/panther-ticketing.postman_collection.json`
con el flujo completo (los identificadores se encadenan automáticamente entre
solicitudes). Importar en Postman: **Import → File**.

Para ejemplos de uso con `curl`, consulte la sección "Usage examples" del `README.md`.
