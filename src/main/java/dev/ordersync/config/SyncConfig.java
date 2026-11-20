package dev.ordersync.config;

import dev.ordersync.erp.ErpUnavailableException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class SyncConfig {

    static final String API_KEY_HEADER = "X-Api-Key";

    @Bean
    RestClient commerceRestClient(RestClient.Builder builder, SyncProperties props) {
        return client(builder, props.commerce(), props);
    }

    @Bean
    RestClient erpRestClient(RestClient.Builder builder, SyncProperties props) {
        return client(builder, props.erp(), props);
    }

    private static RestClient client(RestClient.Builder builder, SyncProperties.Endpoint endpoint, SyncProperties props) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.httpTimeout());
        requestFactory.setReadTimeout(props.httpTimeout());
        return builder
                .baseUrl(endpoint.baseUrl().toString())
                .defaultHeader(API_KEY_HEADER, endpoint.apiKey())
                .requestFactory(requestFactory)
                .build();
    }

    /** Retries only {@link ErpUnavailableException}: a rejected order would be rejected again. */
    @Bean
    RetryTemplate erpRetryTemplate(SyncProperties props) {
        SyncProperties.Retry retry = props.retry();
        return RetryTemplate.builder()
                .maxAttempts(retry.maxAttempts())
                .exponentialBackoff(retry.initialBackoff(), retry.multiplier(), retry.maxBackoff())
                .retryOn(ErpUnavailableException.class)
                .build();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
