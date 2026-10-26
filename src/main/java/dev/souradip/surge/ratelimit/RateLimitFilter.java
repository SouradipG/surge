package dev.souradip.surge.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits each user and each client IP. Both buckets must allow the request.
 *
 * <p>When Redis is unreachable the limiter has to fail one way or the other, and the right
 * answer differs by endpoint. Reads fail open: a short window without limits beats taking the
 * event page down. Writes fail closed: holds and purchases are exactly what bots target.
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter limiter;

    RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/events") || path.startsWith("/holds"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader("X-User-Id");
        try {
            RateLimiter.Decision byIp = limiter.tryAcquire("ip:" + request.getRemoteAddr());
            RateLimiter.Decision byUser = user == null ? byIp : limiter.tryAcquire("user:" + user);
            if (!byIp.allowed() || !byUser.allowed()) {
                long retryMillis = Math.max(byIp.retryAfterMillis(), byUser.retryAfterMillis());
                reject(response, 429, "RATE_LIMITED", "too many requests", Math.max(1, (retryMillis + 999) / 1000));
                return;
            }
        } catch (DataAccessException redisDown) {
            if (!request.getMethod().equals("GET")) {
                log.warn("rate limiter unavailable, rejecting write: {}", redisDown.toString());
                reject(response, 503, "LIMITER_UNAVAILABLE", "try again shortly", 1);
                return;
            }
            log.warn("rate limiter unavailable, letting read through: {}", redisDown.toString());
        }
        chain.doFilter(request, response);
    }

    public static void reject(HttpServletResponse response, int status, String code, String message, long retryAfterSeconds)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
