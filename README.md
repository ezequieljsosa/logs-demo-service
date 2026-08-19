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

- `traceId`: identifica **toda la cadena de llamadas**. Si el request trae el header `X-Trace-Id` lo reusa (asi lo propaga quien lo llamo); si no, lo genera porque es el punto de entrada. Cuando `PingController.callOther()` le pega al otro servicio, reenvia este mismo header — asi el log de A y el log de B para una misma llamada comparten `traceId`, aunque esten en procesos/instancias distintas. Es lo que te deja buscar en Better Stack por un `traceId` y ver el camino completo cruzando servicios.
- `instanceId`: identifica el **proceso** (`RENDER_INSTANCE_ID` en Render, o `INSTANCE_NAME:PORT` en local). Te deja diferenciar de que instancia/servicio vino cada linea.
- `requestId`: identifica **un solo hop** (un request puntual a un solo servicio). A diferencia de `traceId`, no se propaga: cada servicio genera el suyo. Sirve para aislar, dentro de un mismo `traceId`, que parte del log corresponde a la llamada externa vs. a la interna.

Los tres campos van tanto en el patron de consola como en los `mdcFields` que se mandan a Better Stack (quedan como campos estructurados y buscables, no solo texto pegado al mensaje).

### ¿Que es un trace ID?

Es un identificador que se genera una sola vez, en el punto de entrada de una request al sistema, y que se propaga (via header HTTP) a todos los servicios que participan en resolverla. La idea es que cualquier log, de cualquier proceso, que sea parte de esa misma "historia" comparta el mismo trace ID — asi despues podes reconstruir el camino completo aunque haya cruzado 3, 5 o 10 servicios.

Lo que armamos aca es la version mas simple posible de esto: `RequestLoggingFilter` genera un `traceId` si no vino en el header `X-Trace-Id`, y `PingController.callOther()` lo reenvia en ese mismo header al llamar al otro servicio. Sirve para **buscar/filtrar** en Better Stack (Live tail o Search por `traceId:"71150dd4"`) y ver, ordenadas por tiempo, todas las lineas de todos los servicios que participaron en esa llamada.

**Esto no es "distributed tracing" en el sentido completo** (spans + vista waterfall con tiempos y jerarquia padre/hijo, tipo Jaeger/Zipkin/Honeycomb). Better Stack si tiene esa funcionalidad — ingesta traces via **OpenTelemetry (OTLP)** y muestra un waterfall chart por trace — pero require instrumentar la app con el SDK de OpenTelemetry, no alcanza con el appender de Logback que usamos aca. Es un salto de complejidad bastante mayor (otro protocolo, otro SDK, otro modelo de datos) que queda fuera del alcance de este demo, que es sobre *logging* centralizado, no sobre tracing. Si el TP mas adelante quiere ir por ese lado, es un paso natural siguiente, no una alternativa a esto.

El patron de consola tambien incluye `%logger{36}.%method:%line` — no solo la clase (`%logger`), sino el metodo y la linea exacta desde donde se logueo. Tiene un costo real (Logback arma un stacktrace por cada linea para poder ubicar el caller), asi que en un servicio de alto trafico en produccion normalmente se evita — para una demo/TP el costo es insignificante y la ganancia en debuggeabilidad vale la pena.

**`/actuator/health` esta excluido del logging** (`RequestLoggingFilter.shouldNotFilter`). Render (y cualquier uptime-pinger que le agreguen) le pega a ese path todo el tiempo para saber si la instancia esta viva — no podemos bajar esa frecuencia (es infraestructura de Render, no configuracion de la app), pero si podemos evitar que cada uno de esos pings genere 2 lineas de log. Sin este filtro, en un rato el 90% de lo que ves en consola/Better Stack es ruido de health check, no trafico real. Regla general para el TP: **loguear el trafico de negocio, no los health checks.**

## Logging: como esta armado

`src/main/resources/logback-spring.xml` define:

1. Un appender de **consola** siempre activo (para verlo en local y tambien porque Render captura stdout/stderr como logs del servicio).
2. Un appender de **Better Stack** (`com.logtail.logback.LogtailAppender`) que **solo se activa si existe la variable de entorno `BETTERSTACK_SOURCE_TOKEN`**. Si no esta seteada, el proyecto arranca igual y solo loguea por consola — asi nadie se rompe la cabeza si todavia no configuro Better Stack.

Esto se resuelve con un `<if condition='isDefined("BETTERSTACK_SOURCE_TOKEN")'>` que envuelve **dos bloques `<root>` completos** (uno con Better Stack, uno sin), no perfiles de Spring ni `if` en el codigo Java. Dos detalles no obvios de esta parte del XML, documentados como comentario ahi mismo:

- El `<if>` tiene que envolver el `<root>` (asi), no al reves. Ponerlo *adentro* de un `<root>` (`<root><if>...<appender-ref/>...</if></root>`) tira un warning de Logback (`IfNestedWithinSecondPhaseElementSC`) porque no esta soportado — lo vimos en los logs de Render la primera vez que lo desplegamos.
- El atributo `condition="..."` de `<if>` esta deprecado desde Logback 1.5.20 (evalua codigo Java en runtime via Janino, lo cual tuvo vulnerabilidades de seguridad) y se va a remover en 2027. El reemplazo requiere una clase Java custom (`PropertyEvaluator`) — mas complejidad de la que amerita esta demo, asi que por ahora se deja (solo tira warning, sigue andando).

### ¿Que es Better Stack y que es una "Source"?

Better Stack es un SaaS de observabilidad (logs, metricas, incidentes). Para lo que nos interesa aca (centralizar logs), lo unico que hace falta entender es:

- Una **Source** es un "canal" de ingesta: cada Source tiene su propio **Source Token** (para autenticar el envio de logs) y su propio **Ingesting host** (la URL a la que se postean). Todo lo que se manda con ese token a ese host cae en la misma Source.
- No hace falta una Source por servicio — de hecho para este demo, **A y B mandan a la misma Source** (`BETTERSTACK_SOURCE_TOKEN` es igual en ambos), y se distinguen despues por el campo `instanceId` (o `appName`, ver `APP_NAME`). Es una decision de diseño: una Source por *tipo de dato/proyecto*, no por *proceso*.
- **Live tail** es la vista de logs en tiempo real (lo que van a usar la mayoria de las veces para debuggear mientras prueban). **Search** es para consultar logs pasados con filtros.
- Los `mdcFields` que configuramos en el appender (`traceId`, `instanceId`, `requestId`) no son texto libre: llegan como **campos estructurados**, o sea que en Better Stack se pueden filtrar/buscar por `instanceId:"service-a:8080"` en vez de tener que grepear el mensaje.

### ¿Por que no se ve el `traceId` en Live Tail? (mdcFields quedan anidados)

Si miras Live Tail y no aparece `traceId`, no es que no este llegando: el appender `com.logtail:logback-logtail` (lo confirmamos leyendo su codigo fuente) **no manda los `mdcFields` al nivel raiz del JSON** — los anida adentro de un objeto `meta`. O sea que lo que en realidad llega es `meta.traceId`, `meta.instanceId`, `meta.requestId` (y `runtime.class` / `runtime.method` / `runtime.line` para lo de clase/metodo/linea). Cada Source tiene un **`live_tail_pattern`** propio (un template tipo `{app} {runtime.thread} {message}`) que define que columnas se ven inline en Live Tail, y si ese pattern no menciona `{meta.traceId}`, el campo esta ahi (podes verlo si expandis la linea) pero no se muestra en la vista compacta.

Se lo actualizamos a la Source de esta cuenta via API para que se vea directo:

```bash
curl -X PATCH "https://telemetry.betterstack.com/api/v2/sources/<SOURCE_ID>" \
  -H "Authorization: Bearer <TELEMETRY_API_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"live_tail_pattern":"{app} trace={meta.traceId} inst={meta.instanceId} req={meta.requestId} {message}"}'
```

Si creas tu propia Source y esto te vuelve a pasar, es lo primero que hay que revisar: **Integrations/Overview de la Source -> Live tail pattern**, o directamente `GET /api/v2/sources/<id>` para ver el pattern actual.

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
RequestLoggingFilter.doFilterInternal:58 [trace=71150dd4 instance=service-a:8080 req=021f8144] - --> GET /api/call-other
PingController.callOther:46             [trace=71150dd4 instance=service-a:8080 req=021f8144] - call-other invocado, OTHER_SERVICE_URL=http://localhost:8081
RequestLoggingFilter.doFilterInternal:58 [trace=71150dd4 instance=service-b:8081 req=7fc4ae11] - --> GET /api/ping        <- mismo trace, distinto proceso, distinto requestId
PingController.callOther:66             [trace=71150dd4 instance=service-a:8080 req=021f8144] - respuesta de otro servicio recibida en 145ms: {...}
```

Fijate que `trace=71150dd4` es igual en las 4 lineas aunque vengan de 2 procesos distintos — eso es lo que te deja seguir una llamada de punta a punta en Better Stack filtrando por `traceId`. `requestId` en cambio cambia entre A y B: cada uno genera el suyo por hop.

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

> **Nota sobre el plan free:** los servicios se "duermen" tras un rato sin trafico, y el cold start de una app Java/Spring Boot en el free tier es **lento de verdad** — lo medimos: 56s y 105s en dos arranques distintos, la mayor parte en `ApplicationContext` initialization (CPU muy compartida/limitada en ese plan). Si a un alumno el primer request le "cuelga" o tira timeout, probablemente sea esto, no un bug — conviene pegarle a `/actuator/health` primero y esperar antes de asumir que algo esta roto.

### Como se creo (via API, no MCP)

El MCP de Render se registro a mitad de esta sesion de Claude Code, y las herramientas de un MCP recien se cargan al arrancar una sesion nueva — asi que esta vez se uso la **API REST de Render** (`https://api.render.com/v1`) directamente con el mismo API key, vía `curl`: `POST /v1/services` (uno por servicio, `runtime: docker`, apuntando a este repo) y despues `PUT /v1/services/{id}/env-vars/OTHER_SERVICE_URL` en cada uno con la URL del otro (Render ya habia asignado las URLs al crearlos), seguido de un `POST /v1/services/{id}/deploys` para que tomen la env var nueva. En una sesion nueva de Claude Code esto se podria hacer con los tools del MCP en vez de curl crudo.

## Buenas practicas / cosas a tener en cuenta para el TP

Cosas que aprendimos armando esto y que valen para el enunciado:

- **No loguear health checks.** Ver la nota de `/actuator/health` mas arriba — sin filtrarlo, la mayoria del volumen que se manda al logging centralizado es ruido de infraestructura, no trafico real. Aplica a cualquier endpoint de este tipo (`/health`, `/ready`, `/metrics` si lo exponen).
- **La retencion en el free tier de Better Stack es corta.** La Source que usamos para probar (`platform: java`) tiene `logs_retention: 3` (3 dias) segun la propia API de Better Stack. Para el TP alcanza para debuggear al toque, pero no sirve como archivo historico — si necesitan mirar logs de hace una semana, no van a estar.
- **`instanceId` es la clave para que esto sirva con multiples instancias/servicios.** Sin un campo que identifique el proceso (ademas del timestamp y el mensaje), tener logs centralizados de 2+ servicios es peor que tenerlos separados: se pierde el "de donde vino esto". En Render ya viene resuelto con `RENDER_INSTANCE_ID`; si un grupo despliega en otra plataforma, van a tener que buscar el equivalente (o generar un UUID propio al arrancar el proceso).
- **El appender/token de logging centralizado no deberia romper el arranque si falta.** Por eso `BETTERSTACK_SOURCE_TOKEN` es opcional (`<if isDefined(...)>`) — asi un alumno puede desarrollar y correr todo en local sin tener que configurar Better Stack primero, y lo suma recien cuando lo necesita.
- **Cuidado con hardcodear tokens en el repo.** Ver la seccion de Seguridad mas abajo — pasa muy facil en un TP grupal que alguien commitee un `.env` por error. Vale la pena poner `.env` en el `.gitignore` desde el commit inicial del enunciado/template, no despues.

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
