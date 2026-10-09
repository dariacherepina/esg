package com.esg.score.client;

public class CompanyServiceUnavailableException extends RuntimeException {

    public CompanyServiceUnavailableException(Throwable cause) {
        super("company-service is unavailable: " + cause.getMessage(), cause);
    }
}
