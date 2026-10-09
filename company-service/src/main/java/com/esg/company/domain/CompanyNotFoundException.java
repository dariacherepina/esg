package com.esg.company.domain;

public class CompanyNotFoundException extends RuntimeException {

    public CompanyNotFoundException(Long id) {
        super("Company with id " + id + " not found");
    }
}
