package com.esg.report.api;

import com.esg.report.report.Rating;
import com.esg.report.report.ReportGenerator;
import com.esg.report.report.ReportNotFoundException;
import com.esg.report.report.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest {

    private static final Instant T = Instant.parse("2026-10-09T12:00:00Z");
    private final ReportGenerator generator = new ReportGenerator();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService service;

    @Test
    void findByCompanyIdReturnsReport() throws Exception {
        when(service.findByCompanyId(6L)).thenReturn(generator.generate(6L, "Voltara Renewables", 92.6, T, T));

        mockMvc.perform(get("/api/reports/6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Voltara Renewables"))
                .andExpect(jsonPath("$.totalScore").value(92.6))
                .andExpect(jsonPath("$.rating").value("EXCELLENT"))
                .andExpect(jsonPath("$.recommendations", hasSize(3)))
                .andExpect(jsonPath("$.scoreCalculatedAt").value("2026-10-09T12:00:00Z"));
    }

    @Test
    void findAllPassesRatingFilter() throws Exception {
        when(service.findAll(Rating.POOR)).thenReturn(List.of(generator.generate(20L, "Ashgrove Cement", 13.33, T, T)));

        mockMvc.perform(get("/api/reports").param("rating", "POOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].rating").value("POOR"));
    }

    @Test
    void unknownRatingReturns400() throws Exception {
        mockMvc.perform(get("/api/reports").param("rating", "AMAZING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingReportReturns404Problem() throws Exception {
        when(service.findByCompanyId(99L)).thenThrow(new ReportNotFoundException(99L));

        mockMvc.perform(get("/api/reports/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
