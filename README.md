# logs-demo-service

Servicio Spring Boot minimo para probar de punta a punta el esquema de **logging
centralizado** (Datadog) que se les va a pedir a los alumnos del TP,
antes de escribirlo en el enunciado. Sirve para validar que:

- los logs de un proceso local llegan a Datadog con la config mas simple posible (un appender de Logback, sin agentes ni sidecars),
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

- `traceId`: identifica **toda la cadena de llamadas**. Si el request trae el header `X-Trace-Id` lo reusa (asi lo propaga quien lo llamo); si no, lo genera porque es el punto de entrada. Cuando `PingController.callOther()` le pega al otro servicio, reenvia este mismo header — asi el log de A y el log de B para una misma llamada comparten `traceId`, aunque esten en procesos/instancias distintas. Es lo que te deja buscar en Datadog por un `traceId` y ver el camino completo cruzando servicios.
- `instanceId`: identifica el **proceso** (`RENDER_INSTANCE_ID` en Render, o `INSTANCE_NAME:PORT` en local). Te deja diferenciar de que instancia/servicio vino cada linea.
- `requestId`: identifica **un solo hop** (un request puntual a un solo servicio). A diferencia de `traceId`, no se propaga: cada servicio genera el suyo. Sirve para aislar, dentro de un mismo `traceId`, que parte del log corresponde a la llamada externa vs. a la interna.

Los tres campos van tanto en el patron de consola como en el JSON que `DatadogHttpAppender` manda a Datadog — ahi quedan como atributos **de primer nivel** (`traceId`, `instanceId`, `requestId`, no anidados bajo ningun objeto intermedio), asi que en el Log Explorer de Datadog se pueden filtrar/buscar directo (`@traceId:71150dd4`) sin tener que tocar ningun patron de visualizacion.

### ¿Que es un trace ID?

Es un identificador que se genera una sola vez, en el punto de entrada de una request al sistema, y que se propaga (via header HTTP) a todos los servicios que participan en resolverla. La idea es que cualquier log, de cualquier proceso, que sea parte de esa misma "historia" comparta el mismo trace ID — asi despues podes reconstruir el camino completo aunque haya cruzado 3, 5 o 10 servicios.

Lo que armamos aca es la version mas simple posible de esto: `RequestLoggingFilter` genera un `traceId` si no vino en el header `X-Trace-Id`, y `PingController.callOther()` lo reenvia en ese mismo header al llamar al otro servicio. Sirve para **buscar/filtrar** en el Log Explorer de Datadog por `@traceId:71150dd4` y ver, ordenadas por tiempo, todas las lineas de todos los servicios que participaron en esa llamada.

**Esto no es "distributed tracing" en el sentido completo** (spans + vista waterfall con tiempos y jerarquia padre/hijo, tipo Jaeger/Zipkin/Honeycomb). Datadog si tiene esa funcionalidad completa — **APM** (traces via Datadog Tracer o OpenTelemetry/OTLP) con waterfall chart por trace — pero require instrumentar la app con el SDK correspondiente, no alcanza con el appender de Logback que usamos aca. Es un salto de complejidad bastante mayor (otro protocolo/agente, otro SDK, otro modelo de datos, y en Datagog normalmente factura aparte de Log Management) que queda fuera del alcance de este demo, que es sobre *logging* centralizado, no sobre tracing. Si el TP mas adelante quiere ir por ese lado, es un paso natural siguiente, no una alternativa a esto.

El patron de consola tambien incluye `%logger{36}.%method:%line` — no solo la clase (`%logger`), sino el metodo y la linea exacta desde donde se logueo. Tiene un costo real (Logback arma un stacktrace por cada linea para poder ubicar el caller), asi que en un servicio de alto trafico en produccion normalmente se evita — para una demo/TP el costo es insignificante y la ganancia en debuggeabilidad vale la pena. `DatadogHttpAppender` reusa ese mismo dato capturado (`runtime.class`/`runtime.method`/`runtime.line`) para mandarlo tambien como atributos estructurados, no solo pegado al mensaje de consola.

**`/actuator/health` esta excluido del logging** (`RequestLoggingFilter.shouldNotFilter`). Render (y cualquier uptime-pinger que le agreguen) le pega a ese path todo el tiempo para saber si la instancia esta viva — no podemos bajar esa frecuencia (es infraestructura de Render, no configuracion de la app), pero si podemos evitar que cada uno de esos pings genere 2 lineas de log. Sin este filtro, en un rato el 90% de lo que ves en consola/Datadog es ruido de health check, no trafico real. Regla general para el TP: **loguear el trafico de negocio, no los health checks.**

## Logging: como esta armado

`src/main/resources/logback-spring.xml` define:

1. Un appender de **consola** siempre activo (para verlo en local y tambien porque Render captura stdout/stderr como logs del servicio).
2. Un appender de **Datadog** (`DatadogHttpAppender`, clase propia del proyecto en `ar.edu.dds.logsdemo`) que postea cada log a la Logs Intake API v2 de Datadog (`POST https://http-intake.logs.<site>/api/v2/logs`) si existe la variable de entorno `DATADOG_API_KEY`.

No existe un appender de Logback publicado y mantenido para Datadog (a diferencia de `com.logtail:logback-logtail` que usabamos para Better Stack), asi que en vez de un appender de terceros esto es un cliente HTTP minimo escrito a mano (~150 lineas, sin dependencias nuevas: usa `java.net.http.HttpClient`, disponible desde Java 11, y Jackson, que ya viene con `spring-boot-starter-web`).

A diferencia del `BETTERSTACK_SOURCE_TOKEN` de la version anterior, este appender **no necesita el `<if>` condicional de Logback** (ni la dependencia de Janino que eso arrastraba): `DatadogHttpAppender.start()` chequea `DATADOG_API_KEY` el mismo y, si falta, se queda en modo no-op (no arma el `HttpClient`, `append()` no hace nada). Por eso en `logback-spring.xml` el appender se referencia siempre desde `<root>`, sin condicional — un alumno puede desarrollar y correr todo en local sin configurar Datadog primero, igual que antes.

Como con Better Stack, va envuelto en un `AsyncAppender` para que un intake lento no bloquee el thread que esta logueando.

### Datadog: API Key y site

Datadog no tiene el concepto de "Source" de Better Stack — alcanza con una **API Key** de la organizacion, la misma sirve para logs y (si mas adelante suman) metricas.

1. Entrar a Datadog -> **Organization Settings -> API Keys** (`https://app.us5.datadoghq.com/organization-settings/api-keys`, o el dominio que corresponda a tu site).
2. **New Key**, copiarla. Esta es la unica **requerida** — sirve para que el appender *mande* logs (y metricas, si mas adelante se suman).
3. Confirmar el **site** de la cuenta: la URL del dashboard te lo dice (`app.datadoghq.com` = US1, `app.us5.datadoghq.com` = US5, `app.datadoghq.eu` = EU1, `app.us3.datadoghq.com` = US3, etc.). Este proyecto usa la cuenta en **US5** (`us5.datadoghq.com`), que es el default; si en algun momento cambian de cuenta/site, seteá `DATADOG_SITE` (ver env vars abajo) — si no, los requests van a fallar (la API Key no es valida cross-site).

**Application Key: opcional, no la necesita la app.** Solo hace falta si vos (o quien debuggee) quiere *consultar* logs por API en vez de mirar el dashboard — por ejemplo el Logs Search API (`POST /api/v2/logs/events/search`) la exige ademas de la API Key. Para simplemente ver los logs en el Log Explorer (seccion de abajo) no se necesita. Se genera en **Organization Settings -> Application Keys**, es un valor distinto a la API Key (y al "Key ID" que Datadog muestra al lado de cada API Key en la lista — ese ID es solo un identificador, no sirve como credencial).

No hay un equivalente al problema de `mdcFields` anidados que tuvimos con Better Stack: `DatadogHttpAppender` arma el JSON a mano y pone `traceId`/`instanceId`/`requestId` al nivel raiz del payload, asi que Datadog los indexa como atributos de primer nivel sin necesidad de tocar ningun patron de visualizacion — se filtran directo en el Log Explorer con `@traceId:...`.

**No dejes la API Key real en ningun archivo de este repo** (ni en `.env` versionado). Pasala siempre por variable de entorno al correr, tal como se muestra abajo.

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

Fijate que `trace=71150dd4` es igual en las 4 lineas aunque vengan de 2 procesos distintos — eso es lo que te deja seguir una llamada de punta a punta en Datadog filtrando por `traceId`. `requestId` en cambio cambia entre A y B: cada uno genera el suyo por hop.

Esto se verifico funcionando en esta sesion: build OK, 2 procesos levantados en 8080/8081, `/api/ping`, `/api/call-other` y `/api/boom` devolviendo lo esperado (tanto en modo no-op sin `DATADOG_API_KEY`, como con envio real — ver siguiente seccion).

### Con envio a Datadog

**Variable requerida:**

- `DATADOG_API_KEY` — sin esta, el appender arranca en modo no-op (solo consola). Ver arriba como conseguirla.

**Variables opcionales** (todas tienen default, no hace falta tocarlas salvo caso especifico):

| Variable | Default | Cuando cambiarla |
|----------|---------|-------------------|
| `DATADOG_SITE` | `us5.datadoghq.com` | Si la cuenta de Datadog esta en otro site (US1, EU1, etc. — ver arriba) |
| `APP_ENV` | `produccion` en Render (detecta `RENDER=true`), `local` en cualquier otro lado | Para forzar un valor de tag `env` distinto |
| `APP_NAME` | `logs-demo-service` | Para diferenciar servicios en Datadog (`service-a` / `service-b`) |
| `INSTANCE_NAME` | hostname de la maquina | Para diferenciar instancias corriendo en local |
| `OTHER_SERVICE_URL` | vacio (no llama a nadie) | URL del otro servicio, para probar `/api/call-other` |

```bash
export DATADOG_API_KEY=<tu_api_key>
# export DATADOG_SITE=us5.datadoghq.com   # opcional, descomentar solo si tu cuenta esta en otro site
mvn spring-boot:run
curl http://localhost:8080/api/ping
```

También hay un `.env.example` con todas las variables — copialo a `.env` (esta en `.gitignore`, no se commitea) y expórtalo con `export $(cat .env | xargs)` o cargalo con tu herramienta preferida.

### Como ver los logs en Datadog

**Log Explorer:** `https://app.us5.datadoghq.com/logs` (o el subdominio `app.<tu-site>` que corresponda, si no usas US5 — **ojo**, `us5.datadoghq.com` a secas, sin el `app.` adelante, no es la URL del dashboard).

- Busqueda vacia o `*` para ver todo; despues afina con `service:service-a`, `service:service-b`, o `status:error` (para encontrar el de `/api/boom`).
- `@traceId:<valor>` trae **todas las lineas de ambos servicios** para una misma llamada — es el equivalente a lo que haciamos con Better Stack, pero sin tener que tocar ningun patron de visualizacion (`traceId` ya es un atributo de primer nivel, ver seccion de arriba).
- Ajusta el rango de tiempo (arriba a la derecha) al momento en que corriste las pruebas.

**Live Tail:** `Logs -> Live Tail` en el menu lateral — stream en tiempo real, para ver la linea aparecer mientras le pegas a los endpoints.

**Facets (opcional):** por default `traceId`/`instanceId`/`requestId` son buscables (`@traceId:...`) pero no aparecen como columna en la tabla. Para eso: click en un log -> panel de detalle -> buscar el atributo -> **"Create Facet"**. Una vez creado se puede agregar como columna o usar para agrupar/filtrar desde el panel de facets a la izquierda.

Confirmado en esta sesion contra la cuenta real (US5): logs de `service-a` y `service-b` llegaron con `traceId`/`instanceId`/`requestId` intactos, y el de `/api/boom` con `error.kind`/`error.message`/`error.stack` completos (verificado via el Logs Search API, que a diferencia del intake de arriba necesita ademas una **Application Key**, no solo la API Key).

## Deploy en Render

> Esta rama (`feature/datadog-logging`) todavia no se deployeo. Los 2 Web Services de abajo estan desplegados desde `master`, con Better Stack — para pasarlos a Datadog hay que mergear esta rama y actualizar las env vars de cada servicio como se indica abajo.

| Servicio | URL |
|----------|-----|
| `logs-demo-service-a` | https://logs-demo-service-a.onrender.com |
| `logs-demo-service-b` | https://logs-demo-service-b.onrender.com |

Cada servicio (plan `free`, region `oregon`, buildeado con el `Dockerfile` de este repo) deberia tener estas env vars (Dashboard -> el servicio -> Environment):

- `DATADOG_API_KEY` / `DATADOG_SITE` -> la API Key de Datadog (ver seccion de arriba)
- `OTHER_SERVICE_URL` -> la URL publica del otro servicio (cruzado: A apunta a B y viceversa)
- `APP_NAME` -> `service-a` / `service-b`, para diferenciarlos en Datadog

`PORT` y `RENDER_INSTANCE_ID` los pone Render automaticamente — por eso `instanceId` en Render se ve como `srv-xxxx-hibernate-yyyy-zzzz:10000` en vez de `service-a:8080` (usa `RENDER_INSTANCE_ID`, que tiene ese formato). El health check apunta a `/actuator/health`.

Probado end-to-end (con la version de Better Stack en `master`, antes de mergear esta rama):

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
- **Revisar el plan/retencion de la cuenta de Datadog antes de asumir nada.** A diferencia del free tier simple de Better Stack (dias fijos de retencion), Datadog factura Log Management por volumen indexado/ingestado y varia segun si la cuenta esta en trial o en un plan pago — confirmar en `Organization Settings -> Usage` cuanto dura la retencion y cuanta cuota hay antes de mandar trafico real, para no llevarse una sorpresa.
- **`instanceId` es la clave para que esto sirva con multiples instancias/servicios.** Sin un campo que identifique el proceso (ademas del timestamp y el mensaje), tener logs centralizados de 2+ servicios es peor que tenerlos separados: se pierde el "de donde vino esto". En Render ya viene resuelto con `RENDER_INSTANCE_ID`; si un grupo despliega en otra plataforma, van a tener que buscar el equivalente (o generar un UUID propio al arrancar el proceso).
- **El appender/key de logging centralizado no deberia romper el arranque si falta.** Por eso `DatadogHttpAppender` chequea `DATADOG_API_KEY` el mismo en `start()` y se queda en modo no-op si no esta — ni siquiera hace falta un `<if>` en el XML de Logback para lograrlo (a diferencia de Better Stack, que si lo necesitaba). Un alumno puede desarrollar y correr todo en local sin configurar Datadog primero, y lo suma recien cuando lo necesita.
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

- Ningun token quedo escrito en archivos de este repo.
- Para la app, los tokens se pasan siempre por variable de entorno (`DATADOG_API_KEY`), nunca hardcodeados en `application.yml` ni en `logback-spring.xml`.
- `.gitignore` excluye `.env` (dejamos `.env.example` como plantilla).
