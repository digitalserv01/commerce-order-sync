package dev.ordersync.commerce;

import java.util.List;

/** One page of {@code GET /orders}, sorted by {@code modifiedAt} ascending. */
public record CommerceOrderPage(List<CommerceOrder> orders, int page, int totalPages) {

    public boolean isLast() {
        return page + 1 >= totalPages;
    }
}
