package dev.souradip.surge.shedding;

import dev.souradip.surge.ratelimit.RateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs first, before any Redis or database work, so a shed request costs almost nothing. */
@Component
@Order(1)
public class LoadSheddingFilter extends OncePerRequestFilter {

    private final LoadShedder shedder;

    LoadSheddingFilter(LoadShedder shedder) {
        this.shedder = shedder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        LoadShedder.Priority priority = request.getMethod().equals("GET")
                ? LoadShedder.Priority.BROWSE : LoadShedder.Priority.CHECKOUT;
        if (!shedder.tryEnter(priority)) {
            RateLimitFilter.reject(response, 503, "OVERLOADED", "busy, retry shortly", 1);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            shedder.exit();
        }
    }
}
