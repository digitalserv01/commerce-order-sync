package dev.ordersync.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the sync on a fixed delay. A failed run (e.g. commerce API down) is logged and
 * retried on the next tick; the watermark guarantees nothing is skipped meanwhile.
 * Disabled in tests, which call {@link OrderSyncService#syncOnce()} directly.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "sync.scheduler.enabled", havingValue = "true")
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final OrderSyncService syncService;

    public SyncScheduler(OrderSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${sync.poll-interval}")
    public void run() {
        try {
            syncService.syncOnce();
        } catch (RuntimeException e) {
            log.error("Sync run failed, will retry on next tick", e);
        }
    }
}
