package dev.ordersync.mapping;

import dev.ordersync.commerce.CommerceAddress;
import dev.ordersync.commerce.CommerceCustomer;
import dev.ordersync.commerce.CommerceOrder;
import dev.ordersync.commerce.CommerceOrderEntry;
import dev.ordersync.erp.ErpOrderLine;
import dev.ordersync.erp.ErpSalesOrder;
import dev.ordersync.erp.ErpShipTo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Converts a commerce order into an ERP sales order.
 * <p>
 * Pure function, no I/O. It also checks the order's integrity: the ERP total is
 * recomputed from the lines and must match the total the commerce platform
 * charged. A mismatch means the payload is inconsistent, and pushing it would
 * create an invoice that does not match what the customer paid.
 */
@Component
public class OrderMapper {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public ErpSalesOrder toErp(CommerceOrder order) {
        String code = order.code();
        if (isBlank(code)) {
            throw new OrderMappingException("<missing>", "order code is missing");
        }
        if (isBlank(order.currency())) {
            throw new OrderMappingException(code, "currency is missing");
        }
        CommerceCustomer customer = order.customer();
        if (customer == null || isBlank(customer.id())) {
            throw new OrderMappingException(code, "customer id is missing");
        }
        if (order.entries() == null || order.entries().isEmpty()) {
            throw new OrderMappingException(code, "order has no entries");
        }
        if (order.deliveryAddress() == null) {
            throw new OrderMappingException(code, "delivery address is missing");
        }

        List<ErpOrderLine> lines = order.entries().stream()
                .map(entry -> toLine(code, entry))
                .toList();

        BigDecimal shipping = money(order.deliveryCost() == null ? BigDecimal.ZERO : order.deliveryCost());
        BigDecimal netTotal = lines.stream()
                .map(ErpOrderLine::lineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(shipping);

        if (order.totalPrice() != null && netTotal.compareTo(money(order.totalPrice())) != 0) {
            throw new OrderMappingException(code,
                    "total mismatch: lines + shipping = " + netTotal + " but order total = " + money(order.totalPrice()));
        }

        return new ErpSalesOrder(
                code,
                customer.id(),
                customer.email(),
                fullName(customer),
                order.currency().toUpperCase(Locale.ROOT),
                lines,
                shipping,
                netTotal,
                toShipTo(code, order.deliveryAddress())
        );
    }

    private ErpOrderLine toLine(String orderCode, CommerceOrderEntry entry) {
        if (isBlank(entry.productCode())) {
            throw new OrderMappingException(orderCode, "entry " + entry.entryNumber() + " has no product code");
        }
        if (entry.quantity() <= 0) {
            throw new OrderMappingException(orderCode, "entry " + entry.entryNumber() + " has quantity " + entry.quantity());
        }
        if (entry.basePrice() == null) {
            throw new OrderMappingException(orderCode, "entry " + entry.entryNumber() + " has no price");
        }
        BigDecimal unitPrice = money(entry.basePrice());
        return new ErpOrderLine(
                entry.entryNumber() + 1, // commerce entries are 0-based, ERP lines are 1-based
                entry.productCode(),
                isBlank(entry.productName()) ? entry.productCode() : entry.productName(),
                entry.quantity(),
                unitPrice,
                money(unitPrice.multiply(BigDecimal.valueOf(entry.quantity())))
        );
    }

    private ErpShipTo toShipTo(String orderCode, CommerceAddress address) {
        if (isBlank(address.line1()) || isBlank(address.town()) || isBlank(address.countryIsoCode())) {
            throw new OrderMappingException(orderCode, "delivery address is incomplete (line1, town and country are required)");
        }
        return new ErpShipTo(
                address.line1(),
                isBlank(address.line2()) ? null : address.line2(),
                address.postalCode(),
                address.town(),
                address.countryIsoCode().toUpperCase(Locale.ROOT)
        );
    }

    private static String fullName(CommerceCustomer customer) {
        String name = Stream.of(customer.firstName(), customer.lastName())
                .filter(part -> !isBlank(part))
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return name.isEmpty() ? null : name;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
