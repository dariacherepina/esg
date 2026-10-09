package com.esg.score.api;

import com.esg.score.domain.ScoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scores")
public class ScoreController {

    private final ScoreService service;

    public ScoreController(ScoreService service) {
        this.service = service;
    }

    /** Latest stored score of every company that has been calculated, ordered by company id. */
    @GetMapping
    public List<ScoreResponse> findAll() {
        return service.findAll().stream().map(ScoreResponse::from).toList();
    }

    @GetMapping("/{companyId}")
    public ScoreResponse findByCompanyId(@PathVariable Long companyId) {
        return ScoreResponse.from(service.findByCompanyId(companyId));
    }

    /** (Re)calculate one company: fetch from company-service, score, store, publish score.updated. */
    @PostMapping("/{companyId}")
    public ScoreResponse calculate(@PathVariable Long companyId) {
        return ScoreResponse.from(service.calculate(companyId));
    }

    /** (Re)calculate all companies — one score.updated event per company. */
    @PostMapping
    public List<ScoreResponse> calculateAll() {
        return service.calculateAll().stream().map(ScoreResponse::from).toList();
    }
}
