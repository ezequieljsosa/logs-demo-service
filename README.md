# logs-demo-service

Servicio Spring Boot minimo para probar de punta a punta el esquema de **logging
centralizado** (Better Stack) que se les va a pedir a los alumnos del TP,
antes de escribirlo en el enunciado. Sirve para validar que:

- los logs de un proceso local llegan a Better Stack con la config mas simple posible (un appender de Logback, sin agentes ni sidecars),
- el mismo `.jar`, corriendo como **2 procesos distintos** (2 puertos, o 2 servicios separados en Render), puede loguear una llamada de uno a otro y verse correlacionado en el mismo lugar,
- el deploy en Render no requiere configuracion extra mas alla de variables de entorno.

## Endpoints

| Metodo | Path              | Que hace |
|--------|-------------------|----------|
| GET    | `/api/ping`       | Loguea un INFO y devuelve `{instance, message, timestamp}` |
| GET    | `/api/call-other` | Loguea, y si `OTHER_SERVICE_URL` esta seteada, le pega un GET a `<OTHER_SERVICE_URL>/api/ping`. Devuelve la respuesta propia + la del otro servicio + cuanto tardo |
| GET    | `/api/boom`       | Tira una excepcion a proposito y la loguea en nivel ERROR (con stacktrace), para probar filtros de severidad |
| GET    | `/actuator/health`| Health check (lo usa Render para saber si la instancia esta viva) |

Cada request pasa por un `Filter` (`RequestLoggingFilter`) que le pone al MDC:

- `instanceId`: identifica el **proceso** (`RENDER_INSTANCE_ID` en Render, o `INSTANCE_NAME:PORT` en local). Esto es lo que te deja diferenciar, en Better Stack, de que instancia/servicio vino cada linea.
- `requestId`: un id corto por request, para seguir una llamada puntual en los logs.

Esos dos campos van tanto en el patron de consola como en los `mdcFields` que se mandan a Better Stack (quedan como campos estructurados, no solo texto).

## Logging: como esta armado

`src/main/resources/logback-spring.xml` define:

1. Un appender de **consola** siempre activo (para verlo en local y tambien porque Render captura stdout/stderr como logs del servicio).
2. Un appender de **Better Stack** (`com.logtail.logback.LogtailAppender`) que **solo se activa si existe la variable de entorno `BETTERSTACK_SOURCE_TOKEN`**. Si no esta seteada, el proyecto arranca igual y solo loguea por consola — asi nadie se rompe la cabeza si todavia no configuro Better Stack.

Esto se resuelve con un `<if condition='isDefined("BETTERSTACK_SOURCE_TOKEN")'>` (Logback + Janino), sin perfiles de Spring ni `if` en el codigo Java.

### Como conseguir el `BETTERSTACK_SOURCE_TOKEN`

> **Importante:** el token de Better Stack que se usa para el **MCP** es un **Telemetry API token** (team-scoped, para administrar recursos via API/IA) y es distinto del **Source Token** que necesita el appender para *mandar* logs. Confirmado: contra el endpoint de ingesta (`https://in.logs.betterstack.com`) ese token da `401`. Pero el mismo Telemetry API token **si sirve** para listar/crear Sources via la Telemetry API (`GET/POST https://telemetry.betterstack.com/api/v2/sources`), y ahi es donde aparece el Source Token real (campo `attributes.token`) junto con el `attributes.ingesting_host` especifico de esa source.

Dos formas de conseguirlo (misma cuenta, dos caminos):

**Opcion A — Dashboard (mas simple):**
1. Entrar a Better Stack -> **Logs** -> **Sources**.
2. Si ya existe una source de plataforma **Java** (en esta cuenta ya hay una creada, llamada *"App de prueba"*), entrar y copiar su **Source Token** y su **Ingesting host** (algo como `sXXXXX.<region>.betterstackdata.com` — **no** es el host generico `in.logs.betterstack.com`, cada source tiene el suyo propio).
3. Si no existe ninguna, **Connect source** -> plataforma **Java**.

**Opcion B — API (con el Telemetry API token, el mismo que carga el MCP):**
```bash
# Listar sources existentes (incluye token + ingesting_host de cada una)
curl -s https://telemetry.betterstack.com/api/v2/sources \
  -H "Authorization: Bearer <TELEMETRY_API_TOKEN>"

# O crear una nueva
curl -s -X POST https://telemetry.betterstack.com/api/v2/sources \
  -H "Authorization: Bearer <TELEMETRY_API_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"name":"logs-demo-service","platform":"java"}'
```

Ya lo probamos de punta a punta con la source existente de esta cuenta: `GET /api/v2/sources` devolvio `200` con la source Java, y un `POST` directo a su `ingesting_host` con su `token` devolvio `202 Accepted`. Despues corrimos la app local con `BETTERSTACK_SOURCE_TOKEN` + `BETTERSTACK_INGEST_URL` apuntando a esos valores reales y arranco/logueo sin errores — falta solo confirmar visualmente en el dashboard (Live tail) que las lineas de `/api/ping` y `/api/boom` llegaron.

**No dejes el Source Token real en ningun archivo de este repo** (ni en `.env` versionado). Pasalo siempre por variable de entorno al correr, tal como se muestra abajo.

## Correr en local

Requisitos: Java 17+ y Maven (probado con Java 25 y Maven 3.9 en esta maquina).

```bash
cd logs-demo-service
mvn clean package
```

> Nota tecnica: si te tira `error: release version 17 not supported` al compilar, es un bug puntual de compilacion *in-process* en algunas builds de JDK recientes. Ya esta resuelto en el `pom.xml` de este proyecto forzando `<fork>true</fork>` en el `maven-compiler-plugin` (usa el `javac` externo en vez del embebido). Si les vuelve a pasar con otra version de JDK, ese es el fix.

### Una sola instancia

```bash
mvn spring-boot:run
curl http://localhost:8080/api/ping
```

### Dos instancias simulando dos servicios que se llaman entre si

En dos terminales (o dos comandos en background):

```bash
# Terminal 1
INSTANCE_NAME=service-a PORT=8080 OTHER_SERVICE_URL=http://localhost:8081 \
  java -jar target/logs-demo-service-1.0.0.jar

# Terminal 2
INSTANCE_NAME=service-b PORT=8081 OTHER_SERVICE_URL=http://localhost:8080 \
  java -jar target/logs-demo-service-1.0.0.jar
```

Probar:

```bash
curl http://localhost:8080/api/call-other   # A le pega a B
curl http://localhost:8081/api/boom         # genera un ERROR en B
```

En los logs de consola vas a ver, por ejemplo:

```
[instance=service-a:8080 req=eae694ae] - --> GET /api/call-other
[instance=service-a:8080 req=eae694ae] - call-other invocado, OTHER_SERVICE_URL=http://localhost:8081
[instance=service-b:8081 req=717d8d56] - --> GET /api/ping        <- distinto proceso, distinto requestId
[instance=service-a:8080 req=eae694ae] - <-- GET /api/call-other status=200 took=69ms
```

Esto ya se corrio y se verifico funcionando en esta sesion (build OK, 2 procesos levantados en 8080/8081, `/api/ping`, `/api/call-other` y `/api/boom` devolviendo lo esperado).

### Con envio a Better Stack

Una vez que tengas el Source Token y el Ingesting host (ver arriba):

```bash
export BETTERSTACK_SOURCE_TOKEN=<tu_source_token>
export BETTERSTACK_INGEST_URL=https://<tu_ingesting_host>   # ej. https://sXXXXX.eu-fsn-3.betterstackdata.com
mvn spring-boot:run
curl http://localhost:8080/api/ping
```

> Si tu source usa el host generico viejo (`in.logs.betterstack.com`), podes omitir `BETTERSTACK_INGEST_URL` — es el default en `logback-spring.xml`. Las sources nuevas (API v2) suelen traer un host dedicado propio, en ese caso hay que setearlo si o si.

A los pocos segundos el log de `/api/ping` deberia aparecer en el dashboard de Logs de Better Stack (Live tail o Search), con `instance` y `requestId` como campos separados.

También hay un `.env.example` con todas las variables — copialo a `.env` (esta en `.gitignore`, no se commitea) y expórtalo con `export $(cat .env | xargs)` o cargalo con tu herramienta preferida.

## Deploy en Render

Ya esta desplegado como 2 Web Services separados, cada uno buildeado con el `Dockerfile` de este repo (plan `free`, region `oregon`):

| Servicio | URL |
|----------|-----|
| `logs-demo-service-a` | https://logs-demo-service-a.onrender.com |
| `logs-demo-service-b` | https://logs-demo-service-b.onrender.com |

Cada uno tiene estas env vars seteadas (Dashboard -> el servicio -> Environment):

- `BETTERSTACK_SOURCE_TOKEN` / `BETTERSTACK_INGEST_URL` -> la Source de Better Stack (ver seccion de arriba)
- `OTHER_SERVICE_URL` -> la URL publica del otro servicio (cruzado: A apunta a B y viceversa)
- `APP_NAME` -> `service-a` / `service-b`, para diferenciarlos en Better Stack

`PORT` y `RENDER_INSTANCE_ID` los pone Render automaticamente — por eso `instanceId` en Render se ve como `srv-xxxx-hibernate-yyyy-zzzz:10000` en vez de `service-a:8080` (usa `RENDER_INSTANCE_ID`, que tiene ese formato). El health check apunta a `/actuator/health`.

Probado end-to-end:

```bash
curl https://logs-demo-service-a.onrender.com/api/call-other   # A le pega a B
curl https://logs-demo-service-b.onrender.com/api/call-other   # B le pega a A
curl https://logs-demo-service-a.onrender.com/api/boom         # ERROR de prueba
```

> **Nota sobre el plan free:** los servicios se "duermen" tras un rato sin trafico. El primer request despues de eso tarda unos segundos (cold start) — se nota en el `tookMs` de `/api/call-other` cuando el otro servicio estaba dormido.

### Como se creo (via API, no MCP)

El MCP de Render se registro a mitad de esta sesion de Claude Code, y las herramientas de un MCP recien se cargan al arrancar una sesion nueva — asi que esta vez se uso la **API REST de Render** (`https://api.render.com/v1`) directamente con el mismo API key, vía `curl`: `POST /v1/services` (uno por servicio, `runtime: docker`, apuntando a este repo) y despues `PUT /v1/services/{id}/env-vars/OTHER_SERVICE_URL` en cada uno con la URL del otro (Render ya habia asignado las URLs al crearlos), seguido de un `POST /v1/services/{id}/deploys` para que tomen la env var nueva. En una sesion nueva de Claude Code esto se podria hacer con los tools del MCP en vez de curl crudo.

## MCPs configurados en Claude Code

Se registraron en este proyecto (scope **local**, es decir guardados en `~/.claude.json` — **no** en este repo ni en ningun archivo versionable, para no exponer los tokens):

```bash
claude mcp add --transport http render https://mcp.render.com/mcp \
  --header "Authorization: Bearer <RENDER_API_KEY>" --scope local

claude mcp add --transport http betterstack https://mcp.betterstack.com \
  --header "Authorization: Bearer <BETTERSTACK_API_TOKEN>" --scope local
```

Estado verificado con `claude mcp list`: ambos **Connected**. Los tools de estos MCP van a estar disponibles recien en una sesion nueva de Claude Code (la sesion en la que se registraron no los recarga en caliente).

Para removerlos si hace falta: `claude mcp remove render -s local` / `claude mcp remove betterstack -s local`.

## Seguridad / tokens

- Ningun token quedo escrito en archivos de este repo. El de Better Stack y Render solo estan en la config local de Claude Code (`~/.claude.json`, fuera del repo).
- Para la app, los tokens se pasan siempre por variable de entorno (`BETTERSTACK_SOURCE_TOKEN`), nunca hardcodeados en `application.yml` ni en `logback-spring.xml`.
- `.gitignore` excluye `.env` (dejamos `.env.example` como plantilla).
