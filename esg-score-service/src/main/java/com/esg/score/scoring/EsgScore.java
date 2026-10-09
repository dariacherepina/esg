package com.esg.score.scoring;

/** Pillar scores and weighted total, each 0-100, rounded to two decimals. */
public record EsgScore(
        double environmental,
        double social,
        double governance,
        double total
) {
}
