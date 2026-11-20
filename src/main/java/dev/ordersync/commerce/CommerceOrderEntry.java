package dev.ordersync.commerce;

import java.math.BigDecimal;

/** One order line. {@code basePrice} is the unit price, {@code productName} is optional. */
public record CommerceOrderEntry(
        int entryNumber,
        String productCode,
        String productName,
        int quantity,
        BigDecimal basePrice
) {
}
