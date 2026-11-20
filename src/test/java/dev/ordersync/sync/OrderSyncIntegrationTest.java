package dev.ordersync.sync;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end sync against two fake HTTP servers (commerce + ERP) and an in-memory database.
 * No Docker needed.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderSyncIntegrationTest {

    static final WireMockServer commerce = new WireMockServer(options().dynamicPort());
    static final WireMockServer erp = new WireMockServer(options().dynamicPort());

    static {
        commerce.start();
        erp.start();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("sync.commerce.base-url", commerce::baseUrl);
        registry.add("sync.erp.base-url", erp::baseUrl);
    }

    @AfterAll
    static void stopServers() {
        commerce.stop();
        erp.stop();
    }

    @Autowired OrderSyncService syncService;
    @Autowired SyncStateRepository syncStateRepository;
    @Autowired ProcessedOrderRepository processedOrderRepository;
    @Autowired DeadLetterRepository deadLetterRepository;

    @BeforeEach
    void reset() {
        commerce.resetAll();
        erp.resetAll();
        syncStateRepository.deleteAll();
        processedOrderRepository.deleteAll();
        deadLetterRepository.deleteAll();
    }

    // ---------------------------------------------------------------- happy path & idempotency

    @Test
    void syncsEveryOrderAndAdvancesTheWatermark() {
        commercePage(0, 1,
                order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"),
                order("ORD-2", 1, "2026-03-01T11:00:00Z", "25.00"));
        erpAccepts();

        SyncResult result = syncService.syncOnce();

        assertThat(result.synced()).isEqualTo(2);
        assertThat(result.watermark()).isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
        erp.verify(2, postRequestedFor(urlPathEqualTo("/sales-orders")));
        erp.verify(postRequestedFor(urlPathEqualTo("/sales-orders"))
                .withHeader("Idempotency-Key", equalTo("ORD-1-v1"))
                .withHeader("X-Api-Key", equalTo("test-erp-key"))
                .withRequestBody(equalToJson("""
                        {"externalReference":"ORD-1","customerNumber":"C-1","currency":"EUR","netTotal":25.00}
                        """, true, true)));
        commerce.verify(getRequestedFor(urlPathEqualTo("/orders"))
                .withQueryParam("modifiedSince", equalTo("2026-01-01T00:00:00Z"))
                .withHeader("X-Api-Key", equalTo("test-commerce-key")));
        assertThat(processedOrderRepository.count()).isEqualTo(2);
        assertThat(syncStateRepository.findById(OrderSyncService.STREAM).orElseThrow().watermark())
                .isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
    }

    @Test
    void sameOrderPolledTwiceIsSentOnce() {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erpAccepts();

        syncService.syncOnce();
        SyncResult second = syncService.syncOnce();

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.synced()).isZero();
        erp.verify(1, postRequestedFor(urlPathEqualTo("/sales-orders")));
        // the second run asks from the advanced watermark
        commerce.verify(getRequestedFor(urlPathEqualTo("/orders"))
                .withQueryParam("modifiedSince", equalTo("2026-03-01T10:00:00Z")));
    }

    @Test
    void newVersionOfAnOrderIsSentAgainWithANewKey() {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erpAccepts();
        syncService.syncOnce();

        commerce.resetAll();
        commercePage(0, 1, order("ORD-1", 2, "2026-03-02T09:00:00Z", "25.00"));
        SyncResult result = syncService.syncOnce();

        assertThat(result.synced()).isEqualTo(1);
        erp.verify(postRequestedFor(urlPathEqualTo("/sales-orders")).withHeader("Idempotency-Key", equalTo("ORD-1-v1")));
        erp.verify(postRequestedFor(urlPathEqualTo("/sales-orders")).withHeader("Idempotency-Key", equalTo("ORD-1-v2")));
    }

    @Test
    void erpConflictMeansAlreadyCreatedAndCountsAsSynced() {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).willReturn(aResponse()
                .withStatus(409).withHeader("Content-Type", "application/json").withBody("{\"id\":\"SO-EXISTING\"}")));

        SyncResult result = syncService.syncOnce();

        assertThat(result.synced()).isEqualTo(1);
        assertThat(deadLetterRepository.count()).isZero();
        assertThat(processedOrderRepository.findAll()).singleElement()
                .extracting(ProcessedOrder::erpReference).isEqualTo("SO-EXISTING");
    }

    // ---------------------------------------------------------------- failures

    @Test
    void transientErpFailuresAreRetriedUntilSuccess() {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        String scenario = "flaky-erp";
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("failed-once"));
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).inScenario(scenario)
                .whenScenarioStateIs("failed-once").willReturn(aResponse().withStatus(503))
                .willSetStateTo("failed-twice"));
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).inScenario(scenario)
                .whenScenarioStateIs("failed-twice").willReturn(created("SO-1")));

        SyncResult result = syncService.syncOnce();

        assertThat(result.synced()).isEqualTo(1);
        assertThat(deadLetterRepository.count()).isZero();
        erp.verify(3, postRequestedFor(urlPathEqualTo("/sales-orders")));
    }

    @Test
    void erpRejectionIsDeadLetteredWithoutRetry() {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).willReturn(aResponse()
                .withStatus(400).withBody("unknown customer C-1")));

        SyncResult result = syncService.syncOnce();

        assertThat(result.deadLettered()).isEqualTo(1);
        erp.verify(1, postRequestedFor(urlPathEqualTo("/sales-orders")));
        DeadLetter dl = deadLetterRepository.findAll().get(0);
        assertThat(dl.failureType()).isEqualTo(DeadLetter.FailureType.ERP_REJECTED);
        assertThat(dl.errorMessage()).contains("400").contains("unknown customer C-1");
        assertThat(dl.payload()).contains("\"code\":\"ORD-1\"");
        // the bad order does not block the stream
        assertThat(result.watermark()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
    }

    @Test
    void erpStillDownAfterAllRetriesIsDeadLettered() {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).willReturn(aResponse().withStatus(503)));

        SyncResult result = syncService.syncOnce();

        assertThat(result.deadLettered()).isEqualTo(1);
        erp.verify(3, postRequestedFor(urlPathEqualTo("/sales-orders")));
        assertThat(deadLetterRepository.findAll().get(0).failureType())
                .isEqualTo(DeadLetter.FailureType.ERP_UNAVAILABLE);
    }

    @Test
    void invalidOrderDataIsDeadLetteredAndNeverSent() {
        commercePage(0, 1,
                order("ORD-BAD", 1, "2026-03-01T10:00:00Z", "999.00"),   // total does not match lines
                order("ORD-OK", 1, "2026-03-01T11:00:00Z", "25.00"));
        erpAccepts();

        SyncResult result = syncService.syncOnce();

        assertThat(result.deadLettered()).isEqualTo(1);
        assertThat(result.synced()).isEqualTo(1);
        erp.verify(1, postRequestedFor(urlPathEqualTo("/sales-orders")));
        erp.verify(postRequestedFor(urlPathEqualTo("/sales-orders")).withHeader("Idempotency-Key", equalTo("ORD-OK-v1")));
        DeadLetter dl = deadLetterRepository.findAll().get(0);
        assertThat(dl.orderCode()).isEqualTo("ORD-BAD");
        assertThat(dl.failureType()).isEqualTo(DeadLetter.FailureType.MAPPING);
        assertThat(dl.errorMessage()).contains("total mismatch");
    }

    @Test
    void commerceOutageAbortsTheRunWithoutMovingTheWatermark() {
        commerce.stubFor(get(urlPathEqualTo("/orders")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> syncService.syncOnce()).isInstanceOf(RuntimeException.class);

        assertThat(syncStateRepository.findById(OrderSyncService.STREAM)).isEmpty();
        erp.verify(0, postRequestedFor(urlPathEqualTo("/sales-orders")));
    }

    // ---------------------------------------------------------------- pagination

    @Test
    void readsEveryPage() {
        commercePage(0, 2,
                order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"),
                order("ORD-2", 1, "2026-03-01T11:00:00Z", "25.00"));
        commercePage(1, 2,
                order("ORD-3", 1, "2026-03-01T12:00:00Z", "25.00"));
        erpAccepts();

        SyncResult result = syncService.syncOnce();

        assertThat(result.fetched()).isEqualTo(3);
        assertThat(result.synced()).isEqualTo(3);
        assertThat(result.watermark()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
        // both pages read with the run's original watermark
        commerce.verify(2, getRequestedFor(urlPathEqualTo("/orders"))
                .withQueryParam("modifiedSince", equalTo("2026-01-01T00:00:00Z")));
    }

    @Test
    void midRunCommerceFailureKeepsProgressOfCompletedPages() {
        commercePage(0, 2,
                order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"),
                order("ORD-2", 1, "2026-03-01T11:00:00Z", "25.00"));
        commerce.stubFor(get(urlPathEqualTo("/orders")).withQueryParam("page", equalTo("1"))
                .willReturn(aResponse().withStatus(500)));
        erpAccepts();

        assertThatThrownBy(() -> syncService.syncOnce()).isInstanceOf(RuntimeException.class);

        assertThat(processedOrderRepository.count()).isEqualTo(2);
        assertThat(syncStateRepository.findById(OrderSyncService.STREAM).orElseThrow().watermark())
                .isEqualTo(Instant.parse("2026-03-01T11:00:00Z"));
    }

    // ---------------------------------------------------------------- stub helpers

    private void erpAccepts() {
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).willReturn(created("SO-1")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder created(String id) {
        return aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + id + "\"}");
    }

    private void commercePage(int page, int totalPages, String... orders) {
        String body = """
                {"orders":[%s],"page":%d,"totalPages":%d}
                """.formatted(List.of(orders).stream().collect(Collectors.joining(",")), page, totalPages);
        commerce.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** One line: 2 x 10.00 + 5.00 shipping = 25.00. Pass another total to make it inconsistent. */
    private static String order(String code, int version, String modifiedAt, String total) {
        return """
                {"code":"%s","version":%d,"modifiedAt":"%s","currency":"EUR",
                 "customer":{"id":"C-1","email":"jane@example.com","firstName":"Jane","lastName":"Doe"},
                 "entries":[{"entryNumber":0,"productCode":"SKU-A","productName":"Mug","quantity":2,"basePrice":10.00}],
                 "deliveryCost":5.00,"totalPrice":%s,
                 "deliveryAddress":{"line1":"1 Main St","line2":null,"postalCode":"75001","town":"Paris","countryIsoCode":"FR"}}
                """.formatted(code, version, modifiedAt, total);
    }
}
