package com.mywatchmate.abuse;

import com.mywatchmate.common.ProblemResponses;
import com.mywatchmate.common.RateLimiter;
import com.mywatchmate.common.RateLimiters;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Per-IP limits on the public endpoints bots go for, checked before any password hashing or database work.
 * Over a limit: 429 TOO_MANY_ATTEMPTS with Retry-After. Runs inside the security chain just after CORS, so
 * browsers can read the 429 (not a CORS error).
 * <p>
 * The IP is {@code request.getRemoteAddr()}: Tomcat's proxy handling (forward-headers-strategy: native) sets it
 * from X-Forwarded-For, reading from the right and trusting only private-network proxies (Caddy now, a load
 * balancer later), so a client can't pick its own address by sending the header.
 */
@Slf4j
public class RequestGuard extends OncePerRequestFilter {

    private record Guarded(String name, String method, List<PathPattern> paths, List<RateLimiter> limiters) {

        boolean matches(HttpServletRequest request) {
            if (method != null && !method.equalsIgnoreCase(request.getMethod())) {
                return false;
            }
            PathContainer path = PathContainer.parsePath(request.getRequestURI());
            return paths.stream().anyMatch(p -> p.matches(path));
        }
    }

    private final boolean enabled;
    private final List<Guarded> rules = new ArrayList<>();

    public RequestGuard(AbuseProperties props, RateLimiters limits) {
        this.enabled = props.enabled();
        for (AbuseProperties.Rule rule : props.rules()) {
            List<RateLimiter> limiters = new ArrayList<>();
            for (int i = 0; i < rule.limits().size(); i++) {
                AbuseProperties.Limit limit = rule.limits().get(i);
                limiters.add(limits.limiter("ip-" + rule.name() + "-" + i, limit.max(), limit.window()));
            }
            rules.add(new Guarded(rule.name(), rule.method(),
                rule.paths().stream().map(PathPatternParser.defaultInstance::parse).toList(), List.copyOf(limiters)));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (enabled && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            String ip = request.getRemoteAddr();
            Duration wait = Duration.ZERO;
            String hit = null;
            // Stops at the first limit that refuses, so a refused request isn't counted against the others
            for (Guarded rule : rules) {
                if (!wait.isZero() || !rule.matches(request)) {
                    continue;
                }
                for (RateLimiter limiter : rule.limiters()) {
                    wait = limiter.acquire(ip);
                    if (!wait.isZero()) {
                        hit = rule.name();
                        break;
                    }
                }
            }
            if (!wait.isZero()) {
                log.info("Limit {} reached by {} on {} {}", hit, ip, request.getMethod(), request.getRequestURI());
                response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(TooManyAttempts.seconds(wait)));
                ProblemResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS, TooManyAttempts.CODE,
                    TooManyAttempts.MESSAGE);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
