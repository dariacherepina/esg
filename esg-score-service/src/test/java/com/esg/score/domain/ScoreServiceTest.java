package com.esg.score.domain;

import com.esg.score.client.CompanyClient;
import com.esg.score.client.CompanyData;
import com.esg.score.client.CompanyServiceUnavailableException;
import com.esg.score.messaging.ScoreEventPublisher;
import com.esg.score.messaging.ScoreUpdatedEvent;
import com.esg.score.scoring.EsgScoreCalculator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final CompanyData VOLTARA =
            new CompanyData(6L, "Voltara Renewables", "ENERGY", 40, 98, 300, 42, 0, 88);

    private CompanyClient client;
    private CompanyScoreRepository repository;
    private ScoreEventPublisher publisher;
    private SimpleMeterRegistry meterRegistry;
    private ScoreService service;

    @BeforeEach
    void setUp() {
        client = mock(CompanyClient.class);
        repository = mock(CompanyScoreRepository.class);
        publisher = mock(ScoreEventPublisher.class);
        meterRegistry = new SimpleMeterRegistry();
        // real calculator: it is pure and fast, no reason to mock it
        service = new ScoreService(client, new EsgScoreCalculator(), repository, publisher,
                Clock.fixed(NOW, ZoneOffset.UTC), meterRegistry);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void calculateStoresScoreAndPublishesEvent() {
        when(client.fetch(6L)).thenReturn(VOLTARA);
        when(repository.findById(6L)).thenReturn(Optional.empty());

        CompanyScore score = service.calculate(6L);

        assertThat(score.getCompanyId()).isEqualTo(6L);
        assertThat(score.getEnvironmental()).isEqualTo(96.0);
        assertThat(score.getSocial()).isEqualTo(92.0);
        assertThat(score.getGovernance()).isEqualTo(88.0);
        assertThat(score.getTotal()).isEqualTo(92.6);
        assertThat(score.getCalculatedAt()).isEqualTo(NOW);

        ArgumentCaptor<ScoreUpdatedEvent> event = ArgumentCaptor.forClass(ScoreUpdatedEvent.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue())
                .isEqualTo(new ScoreUpdatedEvent(6L, "Voltara Renewables", 92.6, NOW));
    }

    @Test
    void recalculationUpdatesExistingRow() {
        CompanyScore existing = new CompanyScore(6L, "Old Name",
                new EsgScoreCalculator().calculate(new CompanyData(6L, "x", "ENERGY", 1000, 0, 5000, 0, 10, 0).toEsgInput()),
                Instant.parse("2020-01-01T00:00:00Z"));
        when(client.fetch(6L)).thenReturn(VOLTARA);
        when(repository.findById(6L)).thenReturn(Optional.of(existing));

        CompanyScore score = service.calculate(6L);

        assertThat(score).isSameAs(existing);
        assertThat(score.getCompanyName()).isEqualTo("Voltara Renewables");
        assertThat(score.getTotal()).isEqualTo(92.6);
        assertThat(score.getCalculatedAt()).isEqualTo(NOW);
    }

    @Test
    void calculateAllPublishesOneEventPerCompany() {
        CompanyData lumina = new CompanyData(1L, "Lumina Cloud Systems", "TECHNOLOGY", 15, 95, 120, 45, 0, 92);
        when(client.fetchAll()).thenReturn(List.of(lumina, VOLTARA));
        when(repository.findById(any())).thenReturn(Optional.empty());

        List<CompanyScore> scores = service.calculateAll();

        assertThat(scores).extracting(CompanyScore::getCompanyId).containsExactly(1L, 6L);
        verify(publisher, times(2)).publish(any());
        assertThat(meterRegistry.counter("esg.scores.calculated").count()).isEqualTo(2);
    }

    @Test
    void nothingIsStoredOrPublishedWhenCompanyServiceIsDown() {
        when(client.fetch(6L)).thenThrow(new CompanyServiceUnavailableException(new RuntimeException("down")));

        assertThatThrownBy(() -> service.calculate(6L)).isInstanceOf(CompanyServiceUnavailableException.class);
        verify(repository, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void findByCompanyIdThrowsWhenNotCalculatedYet() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByCompanyId(99L)).isInstanceOf(ScoreNotFoundException.class);
    }
}
