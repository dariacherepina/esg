package com.esg.portfolio.domain;

public class PortfolioNotFoundException extends RuntimeException {

    public PortfolioNotFoundException(Long id) {
        super("Portfolio with id " + id + " not found");
    }
}
