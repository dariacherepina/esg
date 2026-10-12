package com.esg.portfolio.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * @EntityGraph loads holdings in the same query as the portfolio: avoids one extra query per
 * portfolio (N+1) and lets callers read holdings after the transaction has ended.
 */
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    @EntityGraph(attributePaths = "holdings")
    List<Portfolio> findAllByOrderByIdAsc();

    @EntityGraph(attributePaths = "holdings")
    Optional<Portfolio> findWithHoldingsById(Long id);

    /** Every portfolio that holds the given company — the ones a score.updated event affects. */
    @EntityGraph(attributePaths = "holdings")
    @Query("select distinct p from Portfolio p join p.holdings h where h.companyId = :companyId")
    List<Portfolio> findAllHoldingCompany(Long companyId);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
