package com.esg.company.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data generates the implementation at startup from the method names.
 * Results are ordered by id so API responses are identical on every run.
 */
public interface CompanyRepository extends JpaRepository<Company, Long> {

    List<Company> findAllByOrderByIdAsc();

    List<Company> findBySectorOrderByIdAsc(Sector sector);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
