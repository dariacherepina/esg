package com.esg.portfolio.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Looks up the current score of a company in esg-score-service when a portfolio is created.
 * Deliberately lenient: if there is no score yet or the service is down, the holding simply starts
 * without a score — the next score.updated event fills it in. Creating a portfolio never fails
 * because of esg-score-service.
 */
@Component
public class ScoreClient {

    private static final Logger log = LoggerFactory.getLogger(ScoreClient.class);

    private final RestClient restClient;

    public ScoreClient(RestClient scoreRestClient) {
        this.restClient = scoreRestClient;
    }

    public Optional<ScoreData> fetch(Long companyId) {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/api/scores/{companyId}", companyId)
                    .retrieve()
                    .body(ScoreData.class));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                log.warn("Unexpected response from esg-score-service for company {}: {}", companyId, e.getStatusCode());
            }
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("esg-score-service unavailable, company {} starts without score: {}", companyId, e.getMessage());
            return Optional.empty();
        }
    }
}
