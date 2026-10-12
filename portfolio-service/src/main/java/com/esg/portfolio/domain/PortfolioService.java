package com.esg.portfolio.domain;

import com.esg.portfolio.client.ScoreClient;
import com.esg.portfolio.client.ScoreData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class PortfolioService {

    private final PortfolioRepository repository;
    private final ScoreClient scoreClient;
    private final TransactionTemplate transaction;
    private final Counter eventsReceived;
    private final Counter portfoliosRecalculated;

    public PortfolioService(PortfolioRepository repository, ScoreClient scoreClient,
                            TransactionTemplate transaction, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.scoreClient = scoreClient;
        this.transaction = transaction;
        this.eventsReceived = Counter.builder("portfolio.score.events")
                .description("score.updated events processed")
                .register(meterRegistry);
        this.portfoliosRecalculated = Counter.builder("portfolio.aggregate.recalculations")
                .description("Portfolio aggregate scores recalculated because of a score.updated event")
                .register(meterRegistry);
    }

    @Transactional(readOnly = true)
    public List<Portfolio> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Portfolio findById(Long id) {
        return repository.findWithHoldingsById(id)
                .orElseThrow(() -> new PortfolioNotFoundException(id));
    }

    public Portfolio create(String name, List<Holding> holdings) {
        if (repository.existsByName(name)) {
            throw new DuplicatePortfolioNameException(name);
        }
        Portfolio portfolio = new Portfolio(name);
        portfolio.replaceHoldings(holdings);
        currentScores(holdings).forEach(score -> apply(portfolio, score));
        return repository.save(portfolio);
    }

    /**
     * HTTP calls to esg-score-service happen BEFORE the transaction starts, so no database
     * connection is held while waiting on the network.
     */
    public Portfolio update(Long id, String name, List<Holding> holdings) {
        List<ScoreData> scores = currentScores(holdings);
        return transaction.execute(status -> {
            Portfolio portfolio = findById(id);
            if (repository.existsByNameAndIdNot(name, id)) {
                throw new DuplicatePortfolioNameException(name);
            }
            portfolio.setName(name);
            portfolio.replaceHoldings(holdings);
            scores.forEach(score -> apply(portfolio, score));
            return portfolio;
        });
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new PortfolioNotFoundException(id);
        }
        repository.deleteById(id);
    }

    /**
     * Applies a score.updated event to every portfolio holding the company. Idempotent: replaying
     * the same (or an older) event changes nothing.
     *
     * @return number of portfolios whose aggregate changed
     */
    @Transactional
    public int applyScoreUpdate(Long companyId, String companyName, double score, Instant calculatedAt) {
        eventsReceived.increment();
        int changed = 0;
        for (Portfolio portfolio : repository.findAllHoldingCompany(companyId)) {
            if (portfolio.applyScore(companyId, companyName, score, calculatedAt)) {
                changed++;
            }
        }
        portfoliosRecalculated.increment(changed);
        return changed;
    }

    /** Current scores of the given holdings' companies; companies without a known score are skipped. */
    private List<ScoreData> currentScores(List<Holding> holdings) {
        return holdings.stream()
                .map(holding -> scoreClient.fetch(holding.getCompanyId()))
                .flatMap(Optional::stream)
                .toList();
    }

    private static void apply(Portfolio portfolio, ScoreData score) {
        portfolio.applyScore(score.companyId(), score.companyName(), score.total(), score.calculatedAt());
    }
}
