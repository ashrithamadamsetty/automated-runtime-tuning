package com.opentext.automatedruntimetuning.web;

import com.opentext.automatedruntimetuning.actuator.RateLimiterActuator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the dynamic rate limit to inbound application traffic and returns HTTP 429
 * when the limit is exceeded.
 *
 * <p>Management and tuning endpoints are deliberately exempt so that the system remains
 * observable and controllable while load is being shed.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterActuator rateLimiterActuator;

    public RateLimitFilter(RateLimiterActuator rateLimiterActuator) {
        this.rateLimiterActuator = rateLimiterActuator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/tuning");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!rateLimiterActuator.tryAcquire()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"rate limit exceeded\",\"limit\":"
                            + rateLimiterActuator.currentLimit() + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
