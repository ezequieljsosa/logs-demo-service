package ar.edu.dds.logsdemo;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manda logs a Datadog Logs Intake (HTTP) cuando {@code DATADOG_API_KEY} esta seteada.
 * Sin key (local/tests) arranca en modo no-op, asi el proyecto siempre levanta sin
 * depender de tener Datadog configurado.
 *
 * <p>No hay un appender de Logback publicado y mantenido para Datadog (a diferencia de
 * {@code com.logtail:logback-logtail} para Better Stack), asi que esto es un cliente
 * minimo de la Logs Intake API v2: https://docs.datadoghq.com/api/latest/logs/
 */
public class DatadogHttpAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final int MAX_MESSAGE_CHARS = 8192;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String apiKey;
    private String service = "app";
    private String env;
    private String site = "us5.datadoghq.com";
    private String hostname = "unknown";
    private HttpClient httpClient;

    public void setService(String service) {
        this.service = service;
    }

    @Override
    public void start() {
        apiKey = System.getenv("DATADOG_API_KEY");
        String siteEnv = System.getenv("DATADOG_SITE");
        if (siteEnv != null && !siteEnv.isBlank()) {
            site = siteEnv;
        }
        env = resolveEnv();
        hostname = resolveHostname();
        if (apiKey == null || apiKey.isBlank()) {
            addInfo("DATADOG_API_KEY no configurado - logs no se exportan a Datadog");
            super.start();
            return;
        }
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        addInfo("Datadog logs habilitados - service=" + service + " env=" + env + " site=" + site);
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (httpClient == null || event == null) {
            return;
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(List.of(toPayload(event)));
            HttpRequest request = HttpRequest.newBuilder(intakeUri())
                    .timeout(Duration.ofSeconds(5))
                    .header("DD-API-KEY", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception exception) {
            addError("No se pudo enviar log a Datadog", exception);
        }
    }

    private URI intakeUri() {
        return URI.create("https://http-intake.logs." + site + "/api/v2/logs");
    }

    Map<String, Object> toPayload(ILoggingEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ddsource", "java");
        payload.put("service", service);
        payload.put("hostname", hostname);
        payload.put("ddtags", "env:" + env + ",app:" + service);
        payload.put("message", truncate(event.getFormattedMessage()));
        payload.put("status", event.getLevel() != null ? event.getLevel().toString().toLowerCase() : "info");
        payload.put("logger.name", event.getLoggerName());
        payload.put("logger.thread_name", event.getThreadName());

        // %method:%line en el patron de consola ya obliga a Logback a capturar esto
        // por evento (ver logback-spring.xml), asi que no tiene costo extra sumarlo aca.
        StackTraceElement[] callerData = event.getCallerData();
        if (callerData != null && callerData.length > 0) {
            StackTraceElement caller = callerData[0];
            payload.put("runtime.class", caller.getClassName());
            payload.put("runtime.method", caller.getMethodName());
            payload.put("runtime.line", caller.getLineNumber());
        }

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            putIfPresent(payload, mdc, "traceId");
            putIfPresent(payload, mdc, "instanceId");
            putIfPresent(payload, mdc, "requestId");
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            payload.put("error.kind", throwable.getClassName());
            payload.put("error.message", throwable.getMessage());
            payload.put("error.stack", ThrowableProxyUtil.asString(throwable));
        }
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, Map<String, String> mdc, String key) {
        String value = mdc.get(key);
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "";
        }
        if (message.length() <= MAX_MESSAGE_CHARS) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_CHARS) + "...(truncated)";
    }

    private static String resolveEnv() {
        String explicit = System.getenv("APP_ENV");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String render = System.getenv("RENDER");
        if (render != null && !render.isBlank()) {
            return "produccion";
        }
        return "local";
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "unknown";
        }
    }
}
