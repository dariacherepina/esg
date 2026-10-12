package com.esg.portfolio.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ScoreClientTest {

    private MockRestServiceServer server;
    private ScoreClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://score-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ScoreClient(builder.build());
    }

    @Test
    void fetchMapsScoreServiceJson() {
        server.expect(requestTo("http://score-service/api/scores/6"))
                .andRespond(withSuccess("""
                        {"companyId":6,"companyName":"Voltara Renewables","environmental":96.0,"social":92.0,
                         "governance":88.0,"total":92.6,"calculatedAt":"2026-10-09T12:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetch(6L)).contains(
                new ScoreData(6L, "Voltara Renewables", 92.6, Instant.parse("2026-10-09T12:00:00Z")));
    }

    @Test
    void notCalculatedYetIsEmpty() {
        server.expect(requestTo("http://score-service/api/scores/6")).andRespond(withResourceNotFound());

        assertThat(client.fetch(6L)).isEmpty();
    }

    @Test
    void serviceErrorIsEmptyInsteadOfFailing() {
        server.expect(requestTo("http://score-service/api/scores/6"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.fetch(6L)).isEmpty();
    }
}
