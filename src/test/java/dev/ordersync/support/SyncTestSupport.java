package dev.ordersync.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.ordersync.sync.DeadLetterRepository;
import dev.ordersync.sync.ProcessedOrderRepository;
import dev.ordersync.sync.SyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared setup: two fake HTTP servers (commerce + ERP) that live for the whole test JVM,
 * an in-memory database wiped before each test, and helpers to stub orders.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class SyncTestSupport {

    protected static final WireMockServer commerce = new WireMockServer(options().dynamicPort());
    protected static final WireMockServer erp = new WireMockServer(options().dynamicPort());

    static {
        commerce.start();
        erp.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            commerce.stop();
            erp.stop();
        }));
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("sync.commerce.base-url", commerce::baseUrl);
        registry.add("sync.erp.base-url", erp::baseUrl);
    }

    @Autowired protected SyncStateRepository syncStateRepository;
    @Autowired protected ProcessedOrderRepository processedOrderRepository;
    @Autowired protected DeadLetterRepository deadLetterRepository;

    @BeforeEach
    void resetState() {
        commerce.resetAll();
        erp.resetAll();
        syncStateRepository.deleteAll();
        processedOrderRepository.deleteAll();
        deadLetterRepository.deleteAll();
    }

    protected static void erpAccepts() {
        erp.stubFor(post(urlPathEqualTo("/sales-orders")).willReturn(created("SO-1")));
    }

    protected static ResponseDefinitionBuilder created(String id) {
        return aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + id + "\"}");
    }

    protected static void commercePage(int page, int totalPages, String... orders) {
        String body = """
                {"orders":[%s],"page":%d,"totalPages":%d}
                """.formatted(List.of(orders).stream().collect(Collectors.joining(",")), page, totalPages);
        commerce.stubFor(get(urlPathEqualTo("/orders"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** One line: 2 x 10.00 + 5.00 shipping = 25.00. Pass another total to make it inconsistent. */
    protected static String order(String code, int version, String modifiedAt, String total) {
        return """
                {"code":"%s","version":%d,"modifiedAt":"%s","currency":"EUR",
                 "customer":{"id":"C-1","email":"jane@example.com","firstName":"Jane","lastName":"Doe"},
                 "entries":[{"entryNumber":0,"productCode":"SKU-A","productName":"Mug","quantity":2,"basePrice":10.00}],
                 "deliveryCost":5.00,"totalPrice":%s,
                 "deliveryAddress":{"line1":"1 Main St","line2":null,"postalCode":"75001","town":"Paris","countryIsoCode":"FR"}}
                """.formatted(code, version, modifiedAt, total);
    }
}
