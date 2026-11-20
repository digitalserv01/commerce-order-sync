package dev.ordersync.commerce;

/** Customer attached to a commerce order. Names are optional (guest checkout). */
public record CommerceCustomer(
        String id,
        String email,
        String firstName,
        String lastName
) {
}
