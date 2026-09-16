package com.mricotta.circuitbreaker.order.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InventoryRestClientConfig {

    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public InventoryRestClientConfig(
            @Value("${app.inventory.base-url}") String baseUrl,
            @Value("${app.inventory.connect-timeout}") Duration connectTimeout,
            @Value("${app.inventory.read-timeout}") Duration readTimeout) {
        this.baseUrl = baseUrl;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /**
     * Built from {@code RestClient.builder()} rather than the autoconfigured {@code RestClient.Builder},
     * which lives in a separate module in Boot 4. The JDK HttpClient is used so the call parks a
     * virtual thread instead of a platform one.
     *
     * <p>The timeouts are the point: without them a hung inventory would block the request forever and
     * there would be no failure for a circuit breaker to react to.
     */
    @Bean
    public RestClient inventoryRestClient() {
        var httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
