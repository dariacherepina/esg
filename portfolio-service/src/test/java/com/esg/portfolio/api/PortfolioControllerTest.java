package com.esg.portfolio.api;

import com.esg.portfolio.domain.DuplicatePortfolioNameException;
import com.esg.portfolio.domain.Holding;
import com.esg.portfolio.domain.InvalidPortfolioException;
import com.esg.portfolio.domain.Portfolio;
import com.esg.portfolio.domain.PortfolioNotFoundException;
import com.esg.portfolio.domain.PortfolioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PortfolioController.class)
class PortfolioControllerTest {

    private static final String VALID_BODY = """
            {"name":"Mixed Energy","holdings":[{"companyId":6,"weight":0.5},{"companyId":10,"weight":0.5}]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PortfolioService service;

    private static Portfolio portfolio(long id) {
        Portfolio portfolio = new Portfolio("Mixed Energy");
        portfolio.replaceHoldings(List.of(new Holding(6L, 0.5), new Holding(10L, 0.5)));
        portfolio.applyScore(6L, "Voltara Renewables", 92.6, Instant.parse("2026-10-09T12:00:00Z"));
        ReflectionTestUtils.setField(portfolio, "id", id);
        return portfolio;
    }

    @Test
    void findByIdReturnsPortfolioWithHoldings() throws Exception {
        when(service.findById(4L)).thenReturn(portfolio(4));

        mockMvc.perform(get("/api/portfolios/4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.aggregateScore").value(92.6))
                .andExpect(jsonPath("$.holdings", hasSize(2)))
                .andExpect(jsonPath("$.holdings[0].companyName").value("Voltara Renewables"))
                .andExpect(jsonPath("$.holdings[0].esgScore").value(92.6))
                .andExpect(jsonPath("$.holdings[1].esgScore").doesNotExist());
    }

    @Test
    void findAllReturnsList() throws Exception {
        when(service.findAll()).thenReturn(List.of(portfolio(1), portfolio(2)));

        mockMvc.perform(get("/api/portfolios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(service.create(eq("Mixed Energy"), anyList())).thenReturn(portfolio(4));

        mockMvc.perform(post("/api/portfolios").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/portfolios/4")));
    }

    @Test
    void invalidInputReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/portfolios").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"holdings\":[{\"companyId\":1,\"weight\":0},{\"weight\":1.5}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors['holdings[0].weight']").exists())
                .andExpect(jsonPath("$.errors['holdings[1].companyId']").exists())
                .andExpect(jsonPath("$.errors['holdings[1].weight']").exists());
        verifyNoInteractions(service);
    }

    @Test
    void emptyHoldingsReturns400() throws Exception {
        mockMvc.perform(post("/api/portfolios").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Empty\",\"holdings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.holdings").exists());
    }

    @Test
    void portfolioRuleViolationReturns400() throws Exception {
        when(service.create(any(), anyList())).thenThrow(new InvalidPortfolioException("weights sum to 1.1"));

        mockMvc.perform(post("/api/portfolios").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("weights sum to 1.1"));
    }

    @Test
    void duplicateNameReturns409() throws Exception {
        when(service.create(any(), anyList())).thenThrow(new DuplicatePortfolioNameException("Mixed Energy"));

        mockMvc.perform(post("/api/portfolios").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void updateUnknownPortfolioReturns404() throws Exception {
        when(service.update(eq(99L), any(), anyList())).thenThrow(new PortfolioNotFoundException(99L));

        mockMvc.perform(put("/api/portfolios/99").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204And404() throws Exception {
        mockMvc.perform(delete("/api/portfolios/4")).andExpect(status().isNoContent());

        doThrow(new PortfolioNotFoundException(99L)).when(service).delete(99L);
        mockMvc.perform(delete("/api/portfolios/99")).andExpect(status().isNotFound());
    }
}
