package dev.ordersync.sync;

import java.time.Instant;

/** What one sync run did. Logged after every run. */
public record SyncResult(int fetched, int synced, int skipped, int deadLettered, Instant watermark) {
}
