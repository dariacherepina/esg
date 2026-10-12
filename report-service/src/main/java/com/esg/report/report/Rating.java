package com.esg.report.report;

/** Categorical rating of a total ESG score (0-100). Bands: [0,40) [40,60) [60,80) [80,100]. */
public enum Rating {
    POOR(0),
    FAIR(40),
    GOOD(60),
    EXCELLENT(80);

    private final double lowerBound;

    Rating(double lowerBound) {
        this.lowerBound = lowerBound;
    }

    public double lowerBound() {
        return lowerBound;
    }

    public static Rating fromScore(double score) {
        if (score >= EXCELLENT.lowerBound) return EXCELLENT;
        if (score >= GOOD.lowerBound) return GOOD;
        if (score >= FAIR.lowerBound) return FAIR;
        return POOR;
    }
}
