package dev.ordersync.admin;

import dev.ordersync.config.SyncProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects {@code /admin/**} with a shared key. Enough for an internal integration service;
 * put it behind your SSO / API gateway in a real deployment.
 */
@Component
public class AdminKeyFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Admin-Key";

    private final byte[] expectedKey;

    public AdminKeyFilter(SyncProperties props) {
        this.expectedKey = props.admin().apiKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        // constant-time comparison, so the key cannot be guessed byte by byte from response timings
        if (provided == null || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing or invalid " + HEADER);
            return;
        }
        chain.doFilter(request, response);
    }
}
