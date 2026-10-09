package com.esg.score.client;

public class CompanyNotFoundException extends RuntimeException {

    public CompanyNotFoundException(Long id) {
        super("Company with id " + id + " not found in company-service");
    }
}
