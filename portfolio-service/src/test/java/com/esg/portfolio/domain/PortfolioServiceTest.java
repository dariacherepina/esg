package com.esg.portfolio.domain;

import com.esg.portfolio.client.ScoreClient;
import com.esg.portfolio.client.ScoreData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioServiceTest {

    private static final Instant T1 = Instant.parse("2026-10-09T12:00:00Z");

    private PortfolioRepository repository;
    private ScoreClient scoreClient;
    private SimpleMeterRegistry meterRegistry;
    private PortfolioService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(PortfolioRepository.class);
        scoreClient = mock(ScoreClient.class);
        meterRegistry = new SimpleMeterRegistry();
        // run the transaction callback directly — no database in a unit test
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        service = new PortfolioService(repository, scoreClient, transaction, meterRegistry);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createFillsCurrentScoresFromScoreService() {
        when(scoreClient.fetch(6L)).thenReturn(Optional.of(new ScoreData(6L, "Voltara Renewables", 92.6, T1)));
        when(scoreClient.fetch(10L)).thenReturn(Optional.of(new ScoreData(10L, "Carbonex Fuels", 21.03, T1)));

        Portfolio portfolio = service.create("Mixed", List.of(new Holding(6L, 0.5), new Holding(10L, 0.5)));

        assertThat(portfolio.getAggregateScore()).isEqualTo(56.82);
        assertThat(portfolio.getHoldings()).extracting(Holding::getCompanyName)
                .containsExactly("Voltara Renewables", "Carbonex Fuels");
    }

    @Test
    void createSucceedsWithoutScoresWhenScoreServiceHasNone() {
        when(scoreClient.fetch(any())).thenReturn(Optional.empty());

        Portfolio portfolio = service.create("New", List.of(new Holding(6L, 1.0)));

        assertThat(portfolio.getAggregateScore()).isNull();
        verify(repository).save(portfolio);
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByName("Taken")).thenReturn(true);

        assertThatThrownBy(() -> service.create("Taken", List.of(new Holding(1L, 1.0))))
                .isInstanceOf(DuplicatePortfolioNameException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateReplacesNameAndHoldings() {
        Portfolio existing = new Portfolio("Old");
        existing.replaceHoldings(List.of(new Holding(1L, 1.0)));
        when(repository.findWithHoldingsById(5L)).thenReturn(Optional.of(existing));
        when(scoreClient.fetch(6L)).thenReturn(Optional.of(new ScoreData(6L, "Voltara Renewables", 92.6, T1)));

        Portfolio updated = service.update(5L, "New", List.of(new Holding(6L, 1.0)));

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getHoldings()).extracting(Holding::getCompanyId).containsExactly(6L);
        assertThat(updated.getAggregateScore()).isEqualTo(92.6);
    }

    @Test
    void updateThrowsWhenPortfolioDoesNotExist() {
        when(repository.findWithHoldingsById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "X", List.of(new Holding(1L, 1.0))))
                .isInstanceOf(PortfolioNotFoundException.class);
    }

    @Test
    void scoreUpdateIsAppliedToEveryPortfolioHoldingTheCompany() {
        Portfolio a = new Portfolio("A");
        a.replaceHoldings(List.of(new Holding(6L, 1.0)));
        Portfolio b = new Portfolio("B");
        b.replaceHoldings(List.of(new Holding(1L, 0.5), new Holding(6L, 0.5)));
        when(repository.findAllHoldingCompany(6L)).thenReturn(List.of(a, b));

        int changed = service.applyScoreUpdate(6L, "Voltara Renewables", 92.6, T1);

        assertThat(changed).isEqualTo(2);
        assertThat(a.getAggregateScore()).isEqualTo(92.6);
        assertThat(b.getAggregateScore()).isEqualTo(92.6);
        assertThat(meterRegistry.counter("portfolio.score.events").count()).isEqualTo(1);
        assertThat(meterRegistry.counter("portfolio.aggregate.recalculations").count()).isEqualTo(2);
    }

    @Test
    void replayedEventChangesNothing() {
        Portfolio a = new Portfolio("A");
        a.replaceHoldings(List.of(new Holding(6L, 1.0)));
        when(repository.findAllHoldingCompany(6L)).thenReturn(List.of(a));

        service.applyScoreUpdate(6L, "Voltara Renewables", 92.6, T1);
        int changedOnReplay = service.applyScoreUpdate(6L, "Voltara Renewables", 92.6, T1);

        assertThat(changedOnReplay).isZero();
    }

    @Test
    void deleteThrowsWhenPortfolioDoesNotExist() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(PortfolioNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }
}
