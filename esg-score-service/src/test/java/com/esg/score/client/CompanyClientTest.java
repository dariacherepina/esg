package com.esg.score.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Fakes company-service at the HTTP level: checks URLs, JSON mapping and error translation. */
class CompanyClientTest {

    private MockRestServiceServer server;
    private CompanyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://company-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CompanyClient(builder.build());
    }

    @Test
    void fetchMapsCompanyServiceJson() {
        server.expect(requestTo("http://company-service/api/companies/6"))
                .andRespond(withSuccess("""
                        {"id":6,"name":"Voltara Renewables","sector":"ENERGY","co2Emissions":40.0,
                         "renewableEnergyPct":98.0,"waterConsumption":300.0,"boardDiversityPct":42.0,
                         "corruptionIncidents":0,"transparencyScore":88.0,"someFutureField":"ignored"}
                        """, MediaType.APPLICATION_JSON));

        CompanyData company = client.fetch(6L);

        assertThat(company).isEqualTo(new CompanyData(6L, "Voltara Renewables", "ENERGY", 40, 98, 300, 42, 0, 88));
        server.verify();
    }

    @Test
    void fetchAllReturnsList() {
        server.expect(requestTo("http://company-service/api/companies"))
                .andRespond(withSuccess("""
                        [{"id":1,"name":"A","sector":"FINANCE","co2Emissions":1,"renewableEnergyPct":1,
                          "waterConsumption":1,"boardDiversityPct":1,"corruptionIncidents":0,"transparencyScore":1},
                         {"id":2,"name":"B","sector":"FINANCE","co2Emissions":1,"renewableEnergyPct":1,
                          "waterConsumption":1,"boardDiversityPct":1,"corruptionIncidents":0,"transparencyScore":1}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.fetchAll()).extracting(CompanyData::id).containsExactly(1L, 2L);
    }

    @Test
    void notFoundBecomesCompanyNotFoundException() {
        server.expect(requestTo("http://company-service/api/companies/99")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.fetch(99L)).isInstanceOf(CompanyNotFoundException.class);
    }

    @Test
    void serverErrorBecomesUnavailable() {
        server.expect(requestTo("http://company-service/api/companies/6"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.fetch(6L)).isInstanceOf(CompanyServiceUnavailableException.class);
    }
}
