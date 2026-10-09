package com.esg.score.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanyScoreRepository extends JpaRepository<CompanyScore, Long> {

    List<CompanyScore> findAllByOrderByCompanyIdAsc();
}
