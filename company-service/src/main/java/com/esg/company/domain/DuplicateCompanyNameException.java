package com.esg.company.domain;

public class DuplicateCompanyNameException extends RuntimeException {

    public DuplicateCompanyNameException(String name) {
        super("Company with name '" + name + "' already exists");
    }
}
