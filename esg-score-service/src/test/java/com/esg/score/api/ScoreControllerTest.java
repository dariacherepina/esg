package com.esg.score.api;

import com.esg.score.client.CompanyNotFoundException;
import com.esg.score.client.CompanyServiceUnavailableException;
import com.esg.score.domain.CompanyScore;
import com.esg.score.domain.ScoreNotFoundException;
import com.esg.score.domain.ScoreService;
import com.esg.score.scoring.EsgScore;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScoreController.class)
class ScoreControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScoreService service;

    private static CompanyScore score(long id, double total) {
        return new CompanyScore(id, "Company " + id, new EsgScore(96, 92, 88, total), NOW);
    }

    @Test
    void calculateReturnsScore() throws Exception {
        when(service.calculate(6L)).thenReturn(score(6, 92.6));

        mockMvc.perform(post("/api/scores/6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyId").value(6))
                .andExpect(jsonPath("$.environmental").value(96.0))
                .andExpect(jsonPath("$.social").value(92.0))
                .andExpect(jsonPath("$.governance").value(88.0))
                .andExpect(jsonPath("$.total").value(92.6))
                .andExpect(jsonPath("$.calculatedAt").value("2026-10-09T12:00:00Z"));
    }

    @Test
    void calculateAllReturnsAllScores() throws Exception {
        when(service.calculateAll()).thenReturn(List.of(score(1, 95.06), score(2, 89.88)));

        mockMvc.perform(post("/api/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void findAllReturnsStoredScores() throws Exception {
        when(service.findAll()).thenReturn(List.of(score(1, 95.06)));

        mockMvc.perform(get("/api/scores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].total").value(95.06));
    }

    @Test
    void unknownCompanyReturns404() throws Exception {
        when(service.calculate(99L)).thenThrow(new CompanyNotFoundException(99L));

        mockMvc.perform(post("/api/scores/99"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void notYetCalculatedReturns404() throws Exception {
        when(service.findByCompanyId(7L)).thenThrow(new ScoreNotFoundException(7L));

        mockMvc.perform(get("/api/scores/7"))
                .andExpect(status().isNotFound());
    }

    @Test
    void companyServiceDownReturns503() throws Exception {
        when(service.calculate(6L)).thenThrow(new CompanyServiceUnavailableException(new RuntimeException("refused")));

        mockMvc.perform(post("/api/scores/6"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void brokerDownReturns503() throws Exception {
        when(service.calculate(6L)).thenThrow(new AmqpConnectException(new RuntimeException("refused")));

        mockMvc.perform(post("/api/scores/6"))
                .andExpect(status().isServiceUnavailable());
    }
}
