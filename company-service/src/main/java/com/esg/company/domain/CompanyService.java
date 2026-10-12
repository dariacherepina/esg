package com.esg.company.domain;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CompanyService {

    private final CompanyRepository repository;

    /**Constructor injection */
    public CompanyService(CompanyRepository repository) {
        this.repository = repository;
    }

    /** All companies ordered by id, optionally filtered by sector (null = no filter). */
    public List<Company> findAll(Sector sector) {
        return sector == null
                ? repository.findAllByOrderByIdAsc()
                : repository.findBySectorOrderByIdAsc(sector);
    }

    public Company findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new CompanyNotFoundException(id));
    }

    @Transactional
    public Company create(Company company) {
        if (repository.existsByName(company.getName())) {
            throw new DuplicateCompanyNameException(company.getName());
        }
        return repository.save(company);
    }

    /** Replaces all fields of an existing company (PUT semantics). */
    @Transactional
    public Company update(Long id, Company changes) {
        Company existing = findById(id);
        if (repository.existsByNameAndIdNot(changes.getName(), id)) {
            throw new DuplicateCompanyNameException(changes.getName());
        }
        existing.setName(changes.getName());
        existing.setSector(changes.getSector());
        existing.setCo2Emissions(changes.getCo2Emissions());
        existing.setRenewableEnergyPct(changes.getRenewableEnergyPct());
        existing.setWaterConsumption(changes.getWaterConsumption());
        existing.setBoardDiversityPct(changes.getBoardDiversityPct());
        existing.setCorruptionIncidents(changes.getCorruptionIncidents());
        existing.setTransparencyScore(changes.getTransparencyScore());
        return existing;
    }

    @Transactional
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new CompanyNotFoundException(id);
        }
        repository.deleteById(id);
    }
}
