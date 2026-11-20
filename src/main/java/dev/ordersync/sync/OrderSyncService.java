package dev.ordersync.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ordersync.commerce.CommerceClient;
import dev.ordersync.commerce.CommerceOrder;
import dev.ordersync.commerce.CommerceOrderPage;
import dev.ordersync.config.SyncProperties;
import dev.ordersync.erp.ErpClient;
import dev.ordersync.erp.ErpRejectedException;
import dev.ordersync.erp.ErpSalesOrder;
import dev.ordersync.erp.ErpUnavailableException;
import dev.ordersync.mapping.OrderMapper;
import dev.ordersync.mapping.OrderMappingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One sync run: read every order modified since the watermark, push each new
 * order version to the ERP, and advance the watermark page by page.
 * <p>
 * Guarantees:
 * <ul>
 *   <li><b>No duplicates:</b> an order version already in {@code processed_orders} is skipped, and the ERP
 *       call carries an idempotency key for the crash window between the ERP accepting and us recording.</li>
 *   <li><b>No silent loss:</b> the watermark only moves past an order once it is synced or dead-lettered.
 *       If the commerce API fails mid-run, the watermark stays where the last completed page left it.</li>
 *   <li><b>No poison pill:</b> one bad order goes to the dead-letter table and the run continues.</li>
 * </ul>
 * Runs never overlap inside one instance (the scheduler and a manual trigger share a lock).
 * Running several instances would need a database lock instead.
 */
@Service
public class OrderSyncService {

    static final String STREAM = "orders";

    private static final Logger log = LoggerFactory.getLogger(OrderSyncService.class);

    public enum Outcome { SYNCED, SKIPPED, DEAD_LETTERED }

    /** Result of replaying one dead letter. {@code newDeadLetterId} is set when the replay failed again. */
    public record ReplayResult(String orderCode, int orderVersion, Outcome outcome, Long newDeadLetterId) {
    }

    /** Thrown when a run is requested while another one is in progress. */
    public static class SyncAlreadyRunningException extends RuntimeException {
        public SyncAlreadyRunningException() {
            super("A sync run is already in progress");
        }
    }

    private final CommerceClient commerceClient;
    private final ErpClient erpClient;
    private final OrderMapper mapper;
    private final RetryTemplate erpRetryTemplate;
    private final SyncStateRepository syncStateRepository;
    private final ProcessedOrderRepository processedOrderRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Instant initialWatermark;
    private final ReentrantLock runLock = new ReentrantLock();

    private final Counter syncedCounter;
    private final Counter skippedCounter;
    private final Counter deadLetteredCounter;

    public OrderSyncService(CommerceClient commerceClient,
                            ErpClient erpClient,
                            OrderMapper mapper,
                            RetryTemplate erpRetryTemplate,
                            SyncStateRepository syncStateRepository,
                            ProcessedOrderRepository processedOrderRepository,
                            DeadLetterRepository deadLetterRepository,
                            ObjectMapper objectMapper,
                            Clock clock,
                            MeterRegistry meterRegistry,
                            SyncProperties props) {
        this.commerceClient = commerceClient;
        this.erpClient = erpClient;
        this.mapper = mapper;
        this.erpRetryTemplate = erpRetryTemplate;
        this.syncStateRepository = syncStateRepository;
        this.processedOrderRepository = processedOrderRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.initialWatermark = props.initialWatermark();
        this.syncedCounter = counter(meterRegistry, "synced");
        this.skippedCounter = counter(meterRegistry, "skipped");
        this.deadLetteredCounter = counter(meterRegistry, "dead_lettered");
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("orders.processed")
                .description("Orders handled by the sync, by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    public SyncResult syncOnce() {
        if (!runLock.tryLock()) {
            throw new SyncAlreadyRunningException();
        }
        try {
            return doSync();
        } finally {
            runLock.unlock();
        }
    }

    public Instant currentWatermark() {
        return syncStateRepository.findById(STREAM).map(SyncState::watermark).orElse(initialWatermark);
    }

    /**
     * Re-sends a dead-lettered order (after the cause was fixed). The old dead letter is removed;
     * if the order fails again, a fresh dead letter with the new error replaces it.
     */
    public ReplayResult replay(long deadLetterId) {
        if (!runLock.tryLock()) {
            throw new SyncAlreadyRunningException();
        }
        try {
            DeadLetter deadLetter = deadLetterRepository.findById(deadLetterId)
                    .orElseThrow(() -> new NoSuchElementException("No dead letter with id " + deadLetterId));
            CommerceOrder order = fromJson(deadLetter.payload());
            deadLetterRepository.delete(deadLetter);

            Processed processed = process(order);
            log.info("Replayed dead letter {} for order {}: {}", deadLetterId, idempotencyKey(order), processed.outcome());
            return new ReplayResult(order.code(), order.version(), processed.outcome(), processed.deadLetterId());
        } finally {
            runLock.unlock();
        }
    }

    private SyncResult doSync() {
        SyncState state = syncStateRepository.findById(STREAM)
                .orElseGet(() -> new SyncState(STREAM, initialWatermark));
        // Every page of this run is read with the same 'since', so pages do not shift under us.
        Instant since = state.watermark();

        int fetched = 0;
        int synced = 0;
        int skipped = 0;
        int deadLettered = 0;
        int page = 0;

        while (true) {
            CommerceOrderPage result = commerceClient.fetchPage(since, page);
            for (CommerceOrder order : result.orders()) {
                fetched++;
                switch (process(order).outcome()) {
                    case SYNCED -> synced++;
                    case SKIPPED -> skipped++;
                    case DEAD_LETTERED -> deadLettered++;
                }
                state.advanceTo(order.modifiedAt());
            }
            // Commit progress after each page: a crash later in the run does not redo this page.
            syncStateRepository.save(state);
            if (result.orders().isEmpty() || result.isLast()) {
                break;
            }
            page++;
        }

        SyncResult summary = new SyncResult(fetched, synced, skipped, deadLettered, state.watermark());
        log.info("Sync run finished: {}", summary);
        return summary;
    }

    private record Processed(Outcome outcome, Long deadLetterId) {
    }

    private Processed process(CommerceOrder order) {
        if (processedOrderRepository.existsByOrderCodeAndOrderVersion(order.code(), order.version())) {
            skippedCounter.increment();
            return new Processed(Outcome.SKIPPED, null);
        }

        ErpSalesOrder erpOrder;
        try {
            erpOrder = mapper.toErp(order);
        } catch (OrderMappingException e) {
            return deadLetter(order, DeadLetter.FailureType.MAPPING, e);
        }

        String idempotencyKey = idempotencyKey(order);
        try {
            String erpReference = erpRetryTemplate.execute(context -> {
                if (context.getRetryCount() > 0) {
                    log.warn("Retrying order {} (attempt {})", idempotencyKey, context.getRetryCount() + 1);
                }
                return erpClient.createSalesOrder(erpOrder, idempotencyKey);
            });
            processedOrderRepository.save(
                    new ProcessedOrder(order.code(), order.version(), erpReference, clock.instant()));
            syncedCounter.increment();
            return new Processed(Outcome.SYNCED, null);
        } catch (ErpRejectedException e) {
            return deadLetter(order, DeadLetter.FailureType.ERP_REJECTED, e);
        } catch (ErpUnavailableException e) {
            return deadLetter(order, DeadLetter.FailureType.ERP_UNAVAILABLE, e);
        }
    }

    private Processed deadLetter(CommerceOrder order, DeadLetter.FailureType type, RuntimeException cause) {
        log.error("Dead-lettering order {} ({}): {}", idempotencyKey(order), type, cause.getMessage());
        DeadLetter saved = deadLetterRepository.save(new DeadLetter(
                order.code(), order.version(), type, cause.getMessage(), toJson(order), clock.instant()));
        deadLetteredCounter.increment();
        return new Processed(Outcome.DEAD_LETTERED, saved.id());
    }

    static String idempotencyKey(CommerceOrder order) {
        return order.code() + "-v" + order.version();
    }

    private String toJson(CommerceOrder order) {
        try {
            return objectMapper.writeValueAsString(order);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise order " + order.code(), e);
        }
    }

    private CommerceOrder fromJson(String payload) {
        try {
            return objectMapper.readValue(payload, CommerceOrder.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored dead-letter payload is not a valid order", e);
        }
    }
}
