package ar.edu.dds.logsdemo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Agrega instanceId y requestId al MDC de cada request, para poder
 * correlacionar logs entre distintos procesos/servicios en Better Stack.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final InstanceInfo instanceInfo;

    public RequestLoggingFilter(InstanceInfo instanceInfo) {
        this.instanceInfo = instanceInfo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Render (y cualquier uptime-pinger) golpea /actuator/health todo el tiempo.
        // Si lo logueamos, ensuciamos la consola y gastamos cuota/retencion en Better Stack
        // con ruido que no aporta nada para debuggear.
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("instanceId", instanceInfo.getInstanceId());
        MDC.put("requestId", requestId);
        long start = System.currentTimeMillis();
        log.info("--> {} {}", request.getMethod(), request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            long took = System.currentTimeMillis() - start;
            log.info("<-- {} {} status={} took={}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), took);
            MDC.clear();
        }
    }
}
