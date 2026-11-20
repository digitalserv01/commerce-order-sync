package dev.ordersync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/**
 * All runtime settings, bound from {@code sync.*}.
 * <p>
 * There are deliberately no defaults in code: endpoints, keys and the initial
 * watermark come from the environment, and a missing value fails at startup
 * instead of silently syncing against the wrong system.
 */
@ConfigurationProperties("sync")
public record SyncProperties(
        Endpoint commerce,
        Endpoint erp,
        int pageSize,
        Instant initialWatermark,
        Duration httpTimeout,
        Admin admin,
        Retry retry
) {

    public record Endpoint(URI baseUrl, String apiKey) {
    }

    /** Key required in the {@code X-Admin-Key} header for every {@code /admin/**} call. */
    public record Admin(String apiKey) {
    }

    /** Backoff for transient ERP failures (5xx, timeouts). */
    public record Retry(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {
    }
}
