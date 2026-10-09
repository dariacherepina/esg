package com.esg.score.domain;

public class ScoreNotFoundException extends RuntimeException {

    public ScoreNotFoundException(Long companyId) {
        super("No score calculated yet for company " + companyId);
    }
}
