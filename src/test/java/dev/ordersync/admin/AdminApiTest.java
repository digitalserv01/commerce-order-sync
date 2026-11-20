package dev.ordersync.admin;

import com.github.tomakehurst.wiremock.client.WireMock;
import dev.ordersync.support.SyncTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminApiTest extends SyncTestSupport {

    private static final String KEY = "test-admin-key";

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry meterRegistry;

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request) {
        return request.header(AdminKeyFilter.HEADER, KEY);
    }

    @Test
    void rejectsCallsWithoutTheAdminKey() throws Exception {
        mvc.perform(get("/admin/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/status").header(AdminKeyFilter.HEADER, "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void manualRunSyncsAndStatusReflectsIt() throws Exception {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erpAccepts();

        mvc.perform(withKey(post("/admin/sync")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.synced").value(1))
                .andExpect(jsonPath("$.watermark").value("2026-03-01T10:00:00Z"));

        mvc.perform(withKey(get("/admin/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processedOrders").value(1))
                .andExpect(jsonPath("$.deadLetters").value(0));
    }

    @Test
    void deadLetterCanBeListedAndReplayedOnceTheErpIsBack() throws Exception {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erp.stubFor(WireMock.post(urlPathEqualTo("/sales-orders")).willReturn(aResponse().withStatus(503)));
        mvc.perform(withKey(post("/admin/sync"))).andExpect(jsonPath("$.deadLettered").value(1));

        long id = deadLetterRepository.findAll().get(0).id();
        mvc.perform(withKey(get("/admin/dead-letters")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].orderCode").value("ORD-1"))
                .andExpect(jsonPath("$[0].failureType").value("ERP_UNAVAILABLE"));

        // ERP recovers
        erp.resetAll();
        erpAccepts();

        mvc.perform(withKey(post("/admin/dead-letters/{id}/replay", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SYNCED"))
                .andExpect(jsonPath("$.newDeadLetterId").doesNotExist());

        assertThat(deadLetterRepository.count()).isZero();
        assertThat(processedOrderRepository.count()).isEqualTo(1);
        erp.verify(1, postRequestedFor(urlPathEqualTo("/sales-orders")));
    }

    @Test
    void replayThatFailsAgainCreatesAFreshDeadLetter() throws Exception {
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erp.stubFor(WireMock.post(urlPathEqualTo("/sales-orders")).willReturn(aResponse().withStatus(400).withBody("bad")));
        mvc.perform(withKey(post("/admin/sync")));
        long oldId = deadLetterRepository.findAll().get(0).id();

        mvc.perform(withKey(post("/admin/dead-letters/{id}/replay", oldId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DEAD_LETTERED"));

        assertThat(deadLetterRepository.findAll()).singleElement()
                .satisfies(dl -> assertThat(dl.id()).isNotEqualTo(oldId));
    }

    @Test
    void replayOfUnknownIdIs404() throws Exception {
        mvc.perform(withKey(post("/admin/dead-letters/{id}/replay", 999_999)))
                .andExpect(status().isNotFound());
    }

    @Test
    void outcomesAreCountedInMetrics() throws Exception {
        double before = meterRegistry.counter("orders.processed", "outcome", "synced").count();
        commercePage(0, 1, order("ORD-1", 1, "2026-03-01T10:00:00Z", "25.00"));
        erpAccepts();

        mvc.perform(withKey(post("/admin/sync")));

        assertThat(meterRegistry.counter("orders.processed", "outcome", "synced").count()).isEqualTo(before + 1);
        mvc.perform(get("/actuator/metrics/orders.processed")).andExpect(status().isOk());
    }
}
