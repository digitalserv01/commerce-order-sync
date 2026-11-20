package dev.ordersync.mapping;

/**
 * The commerce order cannot be turned into a valid ERP order.
 * <p>
 * This is a data problem, not a network problem: retrying will not fix it,
 * so the sync sends these orders straight to the dead-letter table.
 */
public class OrderMappingException extends RuntimeException {

    private final String orderCode;

    public OrderMappingException(String orderCode, String message) {
        super("Order " + orderCode + ": " + message);
        this.orderCode = orderCode;
    }

    public String orderCode() {
        return orderCode;
    }
}
