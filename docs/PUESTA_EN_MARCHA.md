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
podman machine start
podman compose up --build
```

Los comandos `podman` son idénticos en macOS, Linux y Windows (PowerShell).

### Modo B — Servicio en el host (recomendado para desarrollo)

La infraestructura corre en contenedores y el servicio en la máquina, para iterar
sobre el código sin reconstruir la imagen.

**macOS / Linux**
```bash
podman machine start

# Infraestructura en segundo plano (NO incluye el servicio)
podman compose up -d dynamodb-local sqs dynamodb-admin init-resources

# El servicio en la máquina
./gradlew :app-service:bootRun
```

**Windows (PowerShell)**
```powershell
podman machine start

podman compose up -d dynamodb-local sqs dynamodb-admin init-resources

.\gradlew.bat :app-service:bootRun
```

El servicio queda disponible en `http://localhost:8080`.

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
