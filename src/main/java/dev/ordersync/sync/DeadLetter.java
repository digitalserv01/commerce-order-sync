package dev.ordersync.sync;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** An order that could not be synced. The payload is kept so it can be replayed after a fix. */
@Entity
@Table(name = "dead_letters")
public class DeadLetter {

    public enum FailureType {
        /** The commerce data is invalid (e.g. totals do not add up). Fix the data, then replay. */
        MAPPING,
        /** The ERP refused the order (4xx). Fix the cause on either side, then replay. */
        ERP_REJECTED,
        /** The ERP stayed unavailable after every retry. Replay once it is back. */
        ERP_UNAVAILABLE
    }

    private static final int MAX_ERROR_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false)
    private String orderCode;

    @Column(name = "order_version", nullable = false)
    private int orderVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type", nullable = false)
    private FailureType failureType;

    @Column(name = "error_message", nullable = false)
    private String errorMessage;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DeadLetter() {
    }

    public DeadLetter(String orderCode, int orderVersion, FailureType failureType,
                      String errorMessage, String payload, Instant createdAt) {
        this.orderCode = orderCode;
        this.orderVersion = orderVersion;
        this.failureType = failureType;
        this.errorMessage = errorMessage.length() > MAX_ERROR_LENGTH
                ? errorMessage.substring(0, MAX_ERROR_LENGTH)
                : errorMessage;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public Long id() {
        return id;
    }

    public String orderCode() {
        return orderCode;
    }

    public int orderVersion() {
        return orderVersion;
    }

    public FailureType failureType() {
        return failureType;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public String payload() {
        return payload;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
