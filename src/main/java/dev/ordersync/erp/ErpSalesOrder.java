package dev.ordersync.erp;

import java.math.BigDecimal;
import java.util.List;

/**
 * A sales order in the shape the ERP API accepts.
 * <p>
 * {@code externalReference} is the commerce order code: it is how ERP users
 * find the order again, and how the ERP de-duplicates on its side.
 */
public record ErpSalesOrder(
        String externalReference,
        String customerNumber,
        String customerEmail,
        String customerName,
        String currency,
        List<ErpOrderLine> lines,
        BigDecimal shippingCharge,
        BigDecimal netTotal,
        ErpShipTo shipTo
) {
}
