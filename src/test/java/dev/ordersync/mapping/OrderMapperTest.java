package dev.ordersync.mapping;

import dev.ordersync.commerce.CommerceAddress;
import dev.ordersync.commerce.CommerceCustomer;
import dev.ordersync.commerce.CommerceOrder;
import dev.ordersync.commerce.CommerceOrderEntry;
import dev.ordersync.erp.ErpOrderLine;
import dev.ordersync.erp.ErpSalesOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderMapperTest {

    private final OrderMapper mapper = new OrderMapper();

    private static final CommerceCustomer CUSTOMER =
            new CommerceCustomer("C-1001", "jane@example.com", "Jane", "Doe");
    private static final CommerceAddress ADDRESS =
            new CommerceAddress("12 Rue de la Paix", "Apt 4", "75002", "Paris", "fr");

    private static CommerceOrder order(List<CommerceOrderEntry> entries, String deliveryCost, String total) {
        return new CommerceOrder(
                "ORD-001", 3, Instant.parse("2026-11-20T10:00:00Z"), "eur",
                CUSTOMER, entries,
                deliveryCost == null ? null : new BigDecimal(deliveryCost),
                total == null ? null : new BigDecimal(total),
                ADDRESS);
    }

    private static CommerceOrderEntry entry(int n, String sku, String name, int qty, String price) {
        return new CommerceOrderEntry(n, sku, name, qty, new BigDecimal(price));
    }

    @Test
    void mapsMultiLineOrderWithShipping() {
        CommerceOrder order = order(List.of(
                entry(0, "SKU-A", "Ceramic mug", 2, "12.50"),
                entry(1, "SKU-B", "Tea pot", 1, "39.90")
        ), "5.00", "69.90");

        ErpSalesOrder erp = mapper.toErp(order);

        assertThat(erp.externalReference()).isEqualTo("ORD-001");
        assertThat(erp.customerNumber()).isEqualTo("C-1001");
        assertThat(erp.customerEmail()).isEqualTo("jane@example.com");
        assertThat(erp.customerName()).isEqualTo("Jane Doe");
        assertThat(erp.currency()).isEqualTo("EUR");
        assertThat(erp.lines()).extracting(ErpOrderLine::lineNumber, ErpOrderLine::sku, ErpOrderLine::quantity, ErpOrderLine::lineAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "SKU-A", 2, new BigDecimal("25.00")),
                        org.assertj.core.groups.Tuple.tuple(2, "SKU-B", 1, new BigDecimal("39.90")));
        assertThat(erp.shippingCharge()).isEqualByComparingTo("5.00");
        assertThat(erp.netTotal()).isEqualByComparingTo("69.90");
        assertThat(erp.shipTo().street()).isEqualTo("12 Rue de la Paix");
        assertThat(erp.shipTo().street2()).isEqualTo("Apt 4");
        assertThat(erp.shipTo().country()).isEqualTo("FR");
    }

    @Test
    void handlesMissingOptionalFields() {
        CommerceOrder order = new CommerceOrder(
                "ORD-002", 1, Instant.now(), "EUR",
                new CommerceCustomer("C-2002", null, null, "  "),
                List.of(entry(0, "SKU-C", null, 1, "10")),
                null, null,
                new CommerceAddress("1 Main St", "", null, "Lyon", "FR"));

        ErpSalesOrder erp = mapper.toErp(order);

        assertThat(erp.customerName()).isNull();
        assertThat(erp.lines().get(0).description()).isEqualTo("SKU-C");
        assertThat(erp.shippingCharge()).isEqualByComparingTo("0.00");
        assertThat(erp.netTotal()).isEqualByComparingTo("10.00");
        assertThat(erp.shipTo().street2()).isNull();
    }

    @Test
    void roundsMoneyToTwoDecimalsHalfEven() {
        CommerceOrder order = order(List.of(entry(0, "SKU-D", "Tile", 3, "0.335")), null, null);

        ErpSalesOrder erp = mapper.toErp(order);

        // 0.335 -> 0.34 (half-even rounds 0.335 to the even neighbour 0.34), x3 = 1.02
        assertThat(erp.lines().get(0).unitPrice()).isEqualByComparingTo("0.34");
        assertThat(erp.lines().get(0).lineAmount()).isEqualByComparingTo("1.02");
    }

    @Test
    void rejectsTotalMismatch() {
        CommerceOrder order = order(List.of(entry(0, "SKU-A", "Mug", 1, "10.00")), "5.00", "20.00");

        assertThatThrownBy(() -> mapper.toErp(order))
                .isInstanceOf(OrderMappingException.class)
                .hasMessageContaining("ORD-001")
                .hasMessageContaining("total mismatch");
    }

    @Test
    void rejectsOrderWithoutEntries() {
        assertThatThrownBy(() -> mapper.toErp(order(List.of(), null, null)))
                .isInstanceOf(OrderMappingException.class)
                .hasMessageContaining("no entries");
    }

    @Test
    void rejectsMissingDeliveryAddress() {
        CommerceOrder order = new CommerceOrder("ORD-003", 1, Instant.now(), "EUR", CUSTOMER,
                List.of(entry(0, "SKU-A", "Mug", 1, "10.00")), null, null, null);

        assertThatThrownBy(() -> mapper.toErp(order))
                .isInstanceOf(OrderMappingException.class)
                .hasMessageContaining("delivery address is missing");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        CommerceOrder order = order(List.of(entry(0, "SKU-A", "Mug", 0, "10.00")), null, null);

        assertThatThrownBy(() -> mapper.toErp(order))
                .isInstanceOf(OrderMappingException.class)
                .hasMessageContaining("quantity 0");
    }

    @Test
    void rejectsMissingCustomerId() {
        CommerceOrder order = new CommerceOrder("ORD-004", 1, Instant.now(), "EUR",
                new CommerceCustomer(null, "x@example.com", null, null),
                List.of(entry(0, "SKU-A", "Mug", 1, "10.00")), null, null, ADDRESS);

        assertThatThrownBy(() -> mapper.toErp(order))
                .isInstanceOf(OrderMappingException.class)
                .hasMessageContaining("customer id is missing");
    }
}
