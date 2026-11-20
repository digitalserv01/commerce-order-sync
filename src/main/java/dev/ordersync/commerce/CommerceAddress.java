package dev.ordersync.commerce;

/** Delivery address. {@code line2} is optional. */
public record CommerceAddress(
        String line1,
        String line2,
        String postalCode,
        String town,
        String countryIsoCode
) {
}
