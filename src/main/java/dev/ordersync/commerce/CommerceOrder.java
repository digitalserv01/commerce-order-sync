package dev.ordersync.commerce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An order as exposed by the commerce platform's REST API.
 * <p>
 * {@code version} increases every time the order changes on the commerce side
 * (status update, address change, ...). Together with {@code code} it identifies
 * one specific state of the order, which is what we sync.
 */
public record CommerceOrder(
        String code,
        int version,
        Instant modifiedAt,
        String currency,
        CommerceCustomer customer,
        List<CommerceOrderEntry> entries,
        BigDecimal deliveryCost,
        BigDecimal totalPrice,
        CommerceAddress deliveryAddress
) {
}
