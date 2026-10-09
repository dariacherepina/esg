package com.esg.score.config;

import com.esg.score.scoring.EsgScoreCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class ClientConfig {

    /**
     * Built from Spring Boot's RestClient.Builder so outgoing calls are recorded as
     * http_client_requests metrics. Short timeouts: a hanging company-service must not block threads.
     */
    @Bean
    public RestClient companyRestClient(RestClient.Builder builder,
                                        @Value("${company-service.url}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** Injected instead of calling Instant.now() directly, so tests can use a fixed time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** The calculator has no Spring annotations itself; it is registered as a bean here. */
    @Bean
    public EsgScoreCalculator esgScoreCalculator() {
        return new EsgScoreCalculator();
    }
}
