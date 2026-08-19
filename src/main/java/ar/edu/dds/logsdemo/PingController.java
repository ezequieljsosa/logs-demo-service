package ar.edu.dds.logsdemo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api")
public class PingController {

    private static final Logger log = LoggerFactory.getLogger(PingController.class);

    private final InstanceInfo instanceInfo;
    private final RestClient restClient;
    private final String otherServiceUrl;

    public PingController(InstanceInfo instanceInfo,
                           RestClient.Builder restClientBuilder,
                           @Value("${app.other-service-url:}") String otherServiceUrl) {
        this.instanceInfo = instanceInfo;
        this.restClient = restClientBuilder.build();
        this.otherServiceUrl = otherServiceUrl;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        log.info("ping recibido");
        return Map.of(
                "instance", instanceInfo.getInstanceId(),
                "message", "pong",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/call-other")
    public Map<String, Object> callOther() {
        log.info("call-other invocado, OTHER_SERVICE_URL={}", otherServiceUrl);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("self", instanceInfo.getInstanceId());

        if (otherServiceUrl == null || otherServiceUrl.isBlank()) {
            log.warn("OTHER_SERVICE_URL no esta configurada, no se puede llamar a otro servicio");
            result.put("other", null);
            result.put("note", "OTHER_SERVICE_URL no configurada");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            Map<?, ?> otherResponse = restClient.get()
                    .uri(otherServiceUrl + "/api/ping")
                    .retrieve()
                    .body(Map.class);
            long took = System.currentTimeMillis() - start;
            log.info("respuesta de otro servicio recibida en {}ms: {}", took, otherResponse);
            result.put("other", otherResponse);
            result.put("tookMs", took);
        } catch (Exception e) {
            log.error("error llamando a otro servicio en {}", otherServiceUrl, e);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/boom")
    public ResponseEntity<Map<String, Object>> boom() {
        try {
            throw new IllegalStateException("Error de prueba generado a proposito para validar logs de nivel ERROR");
        } catch (Exception e) {
            log.error("excepcion de prueba en /api/boom", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
