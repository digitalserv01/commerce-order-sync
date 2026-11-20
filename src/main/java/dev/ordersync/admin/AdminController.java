package dev.ordersync.admin;

import dev.ordersync.sync.DeadLetter;
import dev.ordersync.sync.DeadLetterRepository;
import dev.ordersync.sync.OrderSyncService;
import dev.ordersync.sync.OrderSyncService.ReplayResult;
import dev.ordersync.sync.OrderSyncService.SyncAlreadyRunningException;
import dev.ordersync.sync.ProcessedOrderRepository;
import dev.ordersync.sync.SyncResult;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/** Operations endpoints: status, manual run, and dead-letter inspection / replay. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final OrderSyncService syncService;
    private final DeadLetterRepository deadLetterRepository;
    private final ProcessedOrderRepository processedOrderRepository;

    public AdminController(OrderSyncService syncService,
                           DeadLetterRepository deadLetterRepository,
                           ProcessedOrderRepository processedOrderRepository) {
        this.syncService = syncService;
        this.deadLetterRepository = deadLetterRepository;
        this.processedOrderRepository = processedOrderRepository;
    }

    public record Status(Instant watermark, long processedOrders, long deadLetters) {
    }

    public record DeadLetterView(Long id, String orderCode, int orderVersion,
                                 DeadLetter.FailureType failureType, String errorMessage, Instant createdAt) {
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(syncService.currentWatermark(),
                processedOrderRepository.count(), deadLetterRepository.count());
    }

    /** Run a sync now instead of waiting for the next scheduled tick. */
    @PostMapping("/sync")
    public SyncResult runNow() {
        return syncService.syncOnce();
    }

    @GetMapping("/dead-letters")
    public List<DeadLetterView> deadLetters() {
        return deadLetterRepository.findAll(Sort.by("id")).stream()
                .map(dl -> new DeadLetterView(dl.id(), dl.orderCode(), dl.orderVersion(),
                        dl.failureType(), dl.errorMessage(), dl.createdAt()))
                .toList();
    }

    @PostMapping("/dead-letters/{id}/replay")
    public ReplayResult replay(@PathVariable long id) {
        return syncService.replay(id);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SyncAlreadyRunningException.class)
    ProblemDetail alreadyRunning(SyncAlreadyRunningException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
