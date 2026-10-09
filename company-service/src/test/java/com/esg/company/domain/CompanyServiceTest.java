package com.esg.company.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository repository;

    @InjectMocks
    private CompanyService service;

    private static Company company(String name) {
        return new Company(name, Sector.ENERGY, 100, 50, 1000, 30, 1, 70);
    }

    @Test
    void findAllWithoutSectorReturnsAllCompanies() {
        List<Company> all = List.of(company("A"), company("B"));
        when(repository.findAllByOrderByIdAsc()).thenReturn(all);

        assertThat(service.findAll(null)).isEqualTo(all);
    }

    @Test
    void findAllWithSectorFiltersBySector() {
        List<Company> energy = List.of(company("A"));
        when(repository.findBySectorOrderByIdAsc(Sector.ENERGY)).thenReturn(energy);

        assertThat(service.findAll(Sector.ENERGY)).isEqualTo(energy);
    }

    @Test
    void findByIdThrowsWhenCompanyDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(CompanyNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void createSavesNewCompany() {
        Company newCompany = company("New Co");
        when(repository.existsByName("New Co")).thenReturn(false);
        when(repository.save(newCompany)).thenReturn(newCompany);

        assertThat(service.create(newCompany)).isSameAs(newCompany);
    }

    @Test
    void createRejectsDuplicateName() {
        when(repository.existsByName("Taken")).thenReturn(true);

        assertThatThrownBy(() -> service.create(company("Taken")))
                .isInstanceOf(DuplicateCompanyNameException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateCopiesAllFieldsToExistingCompany() {
        Company existing = company("Old Name");
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.existsByNameAndIdNot("New Name", 1L)).thenReturn(false);
        Company changes = new Company("New Name", Sector.FINANCE, 10, 90, 100, 45, 0, 95);

        Company updated = service.update(1L, changes);

        assertThat(updated).isSameAs(existing);
        assertThat(updated.getName()).isEqualTo("New Name");
        assertThat(updated.getSector()).isEqualTo(Sector.FINANCE);
        assertThat(updated.getCo2Emissions()).isEqualTo(10);
        assertThat(updated.getRenewableEnergyPct()).isEqualTo(90);
        assertThat(updated.getWaterConsumption()).isEqualTo(100);
        assertThat(updated.getBoardDiversityPct()).isEqualTo(45);
        assertThat(updated.getCorruptionIncidents()).isZero();
        assertThat(updated.getTransparencyScore()).isEqualTo(95);
    }

    @Test
    void updateAllowsKeepingOwnName() {
        when(repository.findById(1L)).thenReturn(Optional.of(company("Same")));
        when(repository.existsByNameAndIdNot("Same", 1L)).thenReturn(false);

        assertThat(service.update(1L, company("Same")).getName()).isEqualTo("Same");
    }

    @Test
    void updateRejectsNameOfAnotherCompany() {
        when(repository.findById(1L)).thenReturn(Optional.of(company("Mine")));
        when(repository.existsByNameAndIdNot("Other", 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, company("Other")))
                .isInstanceOf(DuplicateCompanyNameException.class);
    }

    @Test
    void updateThrowsWhenCompanyDoesNotExist() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, company("X")))
                .isInstanceOf(CompanyNotFoundException.class);
    }

    @Test
    void deleteRemovesExistingCompany() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    void deleteThrowsWhenCompanyDoesNotExist() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOf(CompanyNotFoundException.class);
        verify(repository, never()).deleteById(any());
    }
}
