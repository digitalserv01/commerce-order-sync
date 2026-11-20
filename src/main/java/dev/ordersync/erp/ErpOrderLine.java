package dev.ordersync.erp;

import java.math.BigDecimal;

public record ErpOrderLine(
        int lineNumber,
        String sku,
        String description,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineAmount
) {
}
