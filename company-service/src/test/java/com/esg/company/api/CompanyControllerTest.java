package com.esg.company.api;

import com.esg.company.domain.Company;
import com.esg.company.domain.CompanyNotFoundException;
import com.esg.company.domain.CompanyService;
import com.esg.company.domain.DuplicateCompanyNameException;
import com.esg.company.domain.Sector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CompanyController.class)
class CompanyControllerTest {

    private static final String VALID_BODY = """
            {
              "name": "Voltara Renewables",
              "sector": "ENERGY",
              "co2Emissions": 40,
              "renewableEnergyPct": 98,
              "waterConsumption": 300,
              "boardDiversityPct": 42,
              "corruptionIncidents": 0,
              "transparencyScore": 88
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CompanyService service;

    private static Company company(long id, String name) {
        Company company = new Company(name, Sector.ENERGY, 40, 98, 300, 42, 0, 88);
        ReflectionTestUtils.setField(company, "id", id);
        return company;
    }

    @Test
    void findAllReturnsCompanies() throws Exception {
        when(service.findAll(null)).thenReturn(List.of(company(1, "A"), company(2, "B")));

        mockMvc.perform(get("/api/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[1].name").value("B"));
    }

    @Test
    void findAllPassesSectorFilter() throws Exception {
        when(service.findAll(Sector.ENERGY)).thenReturn(List.of(company(6, "Voltara Renewables")));

        mockMvc.perform(get("/api/companies").param("sector", "ENERGY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void findAllRejectsUnknownSector() throws Exception {
        mockMvc.perform(get("/api/companies").param("sector", "BANANAS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void findByIdReturnsAllFields() throws Exception {
        when(service.findById(6L)).thenReturn(company(6, "Voltara Renewables"));

        mockMvc.perform(get("/api/companies/6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(6))
                .andExpect(jsonPath("$.name").value("Voltara Renewables"))
                .andExpect(jsonPath("$.sector").value("ENERGY"))
                .andExpect(jsonPath("$.co2Emissions").value(40.0))
                .andExpect(jsonPath("$.renewableEnergyPct").value(98.0))
                .andExpect(jsonPath("$.waterConsumption").value(300.0))
                .andExpect(jsonPath("$.boardDiversityPct").value(42.0))
                .andExpect(jsonPath("$.corruptionIncidents").value(0))
                .andExpect(jsonPath("$.transparencyScore").value(88.0));
    }

    @Test
    void findByIdReturns404ProblemWhenMissing() throws Exception {
        when(service.findById(99L)).thenThrow(new CompanyNotFoundException(99L));

        mockMvc.perform(get("/api/companies/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Company with id 99 not found"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(service.create(any())).thenReturn(company(21, "Voltara Renewables"));

        mockMvc.perform(post("/api/companies").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/companies/21")))
                .andExpect(jsonPath("$.id").value(21));
    }

    @Test
    void createReturns409ForDuplicateName() throws Exception {
        when(service.create(any())).thenThrow(new DuplicateCompanyNameException("Voltara Renewables"));

        mockMvc.perform(post("/api/companies").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void createReturns400WithFieldErrorsForInvalidValues() throws Exception {
        String invalid = VALID_BODY
                .replace("\"renewableEnergyPct\": 98", "\"renewableEnergyPct\": 140")
                .replace("\"name\": \"Voltara Renewables\"", "\"name\": \"\"");

        mockMvc.perform(post("/api/companies").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors.renewableEnergyPct").exists())
                .andExpect(jsonPath("$.errors.name").exists());
        verifyNoInteractions(service);
    }

    @Test
    void createReturns400ForMissingField() throws Exception {
        String missing = VALID_BODY.replace("\"transparencyScore\": 88", "\"unused\": 0");

        mockMvc.perform(post("/api/companies").contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.transparencyScore").exists());
    }

    @Test
    void createReturns400ForUnknownSectorInBody() throws Exception {
        String badSector = VALID_BODY.replace("\"ENERGY\"", "\"BANANAS\"");

        mockMvc.perform(post("/api/companies").contentType(MediaType.APPLICATION_JSON).content(badSector))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateReturnsUpdatedCompany() throws Exception {
        when(service.update(eq(6L), any())).thenReturn(company(6, "Voltara Renewables"));

        mockMvc.perform(put("/api/companies/6").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(6));
    }

    @Test
    void updateReturns404WhenMissing() throws Exception {
        when(service.update(eq(99L), any())).thenThrow(new CompanyNotFoundException(99L));

        mockMvc.perform(put("/api/companies/99").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/companies/6"))
                .andExpect(status().isNoContent());
        verify(service).delete(6L);
    }

    @Test
    void deleteReturns404WhenMissing() throws Exception {
        doThrow(new CompanyNotFoundException(99L)).when(service).delete(99L);

        mockMvc.perform(delete("/api/companies/99"))
                .andExpect(status().isNotFound());
    }
}
