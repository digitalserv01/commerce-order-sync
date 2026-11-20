package dev.ordersync.erp;

/** The ERP could not be reached or failed (5xx, timeout, connection error). Retried with backoff. */
public class ErpUnavailableException extends RuntimeException {

    public ErpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
