package dev.ordersync.commerce;

import dev.ordersync.config.SyncProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Reads orders from the commerce platform.
 * <p>
 * {@code modifiedSince} is inclusive: orders sharing the watermark's exact
 * timestamp are fetched again on the next run and skipped by the idempotency
 * check, instead of being silently missed.
 */
@Component
public class CommerceClient {

    private final RestClient restClient;
    private final int pageSize;

    public CommerceClient(@Qualifier("commerceRestClient") RestClient restClient, SyncProperties props) {
        this.restClient = restClient;
        this.pageSize = props.pageSize();
    }

    public CommerceOrderPage fetchPage(Instant modifiedSince, int page) {
        return restClient.get()
                .uri(uri -> uri.path("/orders")
                        .queryParam("modifiedSince", modifiedSince.toString())
                        .queryParam("page", page)
                        .queryParam("pageSize", pageSize)
                        .build())
                .retrieve()
                .body(CommerceOrderPage.class);
    }
}
