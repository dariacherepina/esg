package com.esg.portfolio.domain;

public class DuplicatePortfolioNameException extends RuntimeException {

    public DuplicatePortfolioNameException(String name) {
        super("Portfolio with name '" + name + "' already exists");
    }
}
