package com.esg.report.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReportStoreTest {

    private static final Instant T1 = Instant.parse("2026-10-09T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-09T13:00:00Z");

    private final ReportGenerator generator = new ReportGenerator();
    private final ReportStore store = new ReportStore();

    private Report report(long companyId, double score, Instant calculatedAt) {
        return generator.generate(companyId, "Company " + companyId, score, calculatedAt, calculatedAt);
    }

    @Test
    void newerReportReplacesOlder() {
        store.saveIfNewer(report(6, 50, T1));

        assertThat(store.saveIfNewer(report(6, 92.6, T2))).isTrue();
        assertThat(store.find(6L)).get().extracting(Report::totalScore).isEqualTo(92.6);
    }

    @Test
    void olderOrDuplicateReportIsIgnored() {
        store.saveIfNewer(report(6, 92.6, T2));

        assertThat(store.saveIfNewer(report(6, 10, T1))).isFalse();
        assertThat(store.saveIfNewer(report(6, 10, T2))).isFalse();
        assertThat(store.find(6L)).get().extracting(Report::totalScore).isEqualTo(92.6);
    }

    @Test
    void findAllIsOrderedByCompanyIdAndFiltersByRating() {
        store.saveIfNewer(report(20, 13.33, T1));
        store.saveIfNewer(report(1, 95.06, T1));
        store.saveIfNewer(report(6, 92.6, T1));

        assertThat(store.findAll(null)).extracting(Report::companyId).containsExactly(1L, 6L, 20L);
        assertThat(store.findAll(Rating.POOR)).extracting(Report::companyId).containsExactly(20L);
        assertThat(store.findAll(Rating.GOOD)).isEmpty();
    }

    /** The listener thread writes while HTTP threads read: the newest score must win regardless of order. */
    @Test
    void concurrentUpdatesKeepTheNewestScore() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 1; i <= 1000; i++) {
            int n = i;
            pool.submit(() -> {
                start.await();
                return store.saveIfNewer(report(6, n % 100, base.plusSeconds(n)));
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(store.find(6L)).get().extracting(Report::scoreCalculatedAt).isEqualTo(base.plusSeconds(1000));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void emptyStore() {
        assertThat(store.find(1L)).isEmpty();
        assertThat(store.findAll(null)).isEqualTo(List.of());
    }
}
