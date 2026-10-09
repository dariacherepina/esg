package com.esg.score.domain;

import com.esg.score.client.CompanyClient;
import com.esg.score.client.CompanyData;
import com.esg.score.messaging.ScoreEventPublisher;
import com.esg.score.messaging.ScoreUpdatedEvent;
import com.esg.score.scoring.EsgScore;
import com.esg.score.scoring.EsgScoreCalculator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Fetch raw data -> calculate -> persist -> publish score.updated.
 * Not @Transactional as a whole: the score is saved (and committed) by the repository first,
 * and the event is published only afterwards, so consumers never hear about an unsaved score.
 */
@Service
public class ScoreService {

    private final CompanyClient companyClient;
    private final EsgScoreCalculator calculator;
    private final CompanyScoreRepository repository;
    private final ScoreEventPublisher publisher;
    private final Clock clock;
    private final Counter calculations;

    public ScoreService(CompanyClient companyClient, EsgScoreCalculator calculator,
                        CompanyScoreRepository repository, ScoreEventPublisher publisher,
                        Clock clock, MeterRegistry meterRegistry) {
        this.companyClient = companyClient;
        this.calculator = calculator;
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.calculations = Counter.builder("esg.scores.calculated")
                .description("Number of ESG score calculations")
                .register(meterRegistry);
    }

    public CompanyScore calculate(Long companyId) {
        return calculate(companyClient.fetch(companyId));
    }

    /** Recalculates every company known to company-service (one REST call, then one event per company). */
    public List<CompanyScore> calculateAll() {
        return companyClient.fetchAll().stream()
                .map(this::calculate)
                .toList();
    }

    public List<CompanyScore> findAll() {
        return repository.findAllByOrderByCompanyIdAsc();
    }

    public CompanyScore findByCompanyId(Long companyId) {
        return repository.findById(companyId)
                .orElseThrow(() -> new ScoreNotFoundException(companyId));
    }

    private CompanyScore calculate(CompanyData company) {
        EsgScore score = calculator.calculate(company.toEsgInput());
        Instant now = clock.instant();

        CompanyScore entity = repository.findById(company.id())
                .map(existing -> {
                    existing.update(company.name(), score, now);
                    return existing;
                })
                .orElseGet(() -> new CompanyScore(company.id(), company.name(), score, now));
        CompanyScore saved = repository.save(entity);

        publisher.publish(new ScoreUpdatedEvent(saved.getCompanyId(), saved.getCompanyName(),
                saved.getTotal(), saved.getCalculatedAt()));
        calculations.increment();
        return saved;
    }
}
