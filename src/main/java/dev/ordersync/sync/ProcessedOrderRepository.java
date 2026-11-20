package dev.ordersync.sync;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedOrderRepository extends JpaRepository<ProcessedOrder, ProcessedOrder.Key> {

    boolean existsByOrderCodeAndOrderVersion(String orderCode, int orderVersion);
}
