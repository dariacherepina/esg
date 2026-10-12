package com.esg.portfolio.domain;

public class InvalidPortfolioException extends RuntimeException {

    public InvalidPortfolioException(String message) {
        super(message);
    }
}
