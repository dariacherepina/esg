package com.esg.report.report;

public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException(Long companyId) {
        super("No report for company " + companyId + " yet");
    }
}
