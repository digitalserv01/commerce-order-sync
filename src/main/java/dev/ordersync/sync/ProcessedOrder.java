package dev.ordersync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;

/** An order version the ERP has accepted. Its presence is what makes the sync idempotent. */
@Entity
@Table(name = "processed_orders")
@IdClass(ProcessedOrder.Key.class)
public class ProcessedOrder {

    @Id
    @Column(name = "order_code")
    private String orderCode;

    @Id
    @Column(name = "order_version")
    private int orderVersion;

    @Column(name = "erp_reference")
    private String erpReference;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedOrder() {
    }

    public ProcessedOrder(String orderCode, int orderVersion, String erpReference, Instant processedAt) {
        this.orderCode = orderCode;
        this.orderVersion = orderVersion;
        this.erpReference = erpReference;
        this.processedAt = processedAt;
    }

    public String orderCode() {
        return orderCode;
    }

    public int orderVersion() {
        return orderVersion;
    }

    public String erpReference() {
        return erpReference;
    }

    public record Key(String orderCode, int orderVersion) implements Serializable {

        @SuppressWarnings("unused") // required by JPA
        Key() {
            this(null, 0);
        }
    }
}
