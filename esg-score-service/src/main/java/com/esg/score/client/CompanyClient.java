package com.esg.score.client;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;

/** Synchronous REST client for company-service. */
@Component
public class CompanyClient {

    private final RestClient restClient;

    public CompanyClient(RestClient companyRestClient) {
        this.restClient = companyRestClient;
    }

    public CompanyData fetch(Long id) {
        try {
            return restClient.get()
                    .uri("/api/companies/{id}", id)
                    .retrieve()
                    .body(CompanyData.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new CompanyNotFoundException(id);
            }
            throw new CompanyServiceUnavailableException(e);
        } catch (RestClientException e) {
            throw new CompanyServiceUnavailableException(e);
        }
    }

    public List<CompanyData> fetchAll() {
        try {
            CompanyData[] companies = restClient.get()
                    .uri("/api/companies")
                    .retrieve()
                    .body(CompanyData[].class);
            return companies == null ? List.of() : Arrays.asList(companies);
        } catch (RestClientException e) {
            throw new CompanyServiceUnavailableException(e);
        }
    }
}
