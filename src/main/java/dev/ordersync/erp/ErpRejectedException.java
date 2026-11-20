package dev.ordersync.erp;

/** The ERP refused the order (4xx). Not retried: the same payload would be refused again. */
public class ErpRejectedException extends RuntimeException {

    private final int status;

    public ErpRejectedException(int status, String responseBody) {
        super("ERP rejected the order with HTTP " + status + ": " + responseBody);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
