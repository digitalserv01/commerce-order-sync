package dev.ordersync.erp;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

/**
 * Creates sales orders in the ERP.
 * <p>
 * Every call carries an {@code Idempotency-Key}. If the sync crashes after the
 * ERP accepted an order but before we recorded it, the next run re-sends the
 * same key and the ERP answers 409 Conflict, which we treat as success.
 */
@Component
public class ErpClient {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final int CONFLICT = 409;

    private final RestClient restClient;

    public ErpClient(@Qualifier("erpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /** @return the ERP's id for the sales order */
    public String createSalesOrder(ErpSalesOrder order, String idempotencyKey) {
        try {
            ErpCreateResponse response = restClient.post()
                    .uri("/sales-orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(order)
                    .retrieve()
                    .onStatus(status -> status.value() == CONFLICT, (request, response1) -> {
                        // already created with this key: read the existing id from the body
                    })
                    .onStatus(status -> status.is4xxClientError(), (request, res) -> {
                        throw new ErpRejectedException(res.getStatusCode().value(),
                                StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8));
                    })
                    .onStatus(status -> status.is5xxServerError(), (request, res) -> {
                        throw new ErpUnavailableException("ERP returned HTTP " + res.getStatusCode().value(), null);
                    })
                    .body(ErpCreateResponse.class);
            return response.id();
        } catch (ResourceAccessException e) {
            throw new ErpUnavailableException("ERP not reachable: " + e.getMessage(), e);
        }
    }

    record ErpCreateResponse(String id) {
    }
}
