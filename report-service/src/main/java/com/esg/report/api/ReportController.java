package com.esg.report.api;

import com.esg.report.report.Rating;
import com.esg.report.report.Report;
import com.esg.report.report.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only: reports are only ever created by score.updated events. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping
    public List<Report> findAll(@RequestParam(required = false) Rating rating) {
        return service.findAll(rating);
    }

    @GetMapping("/{companyId}")
    public Report findByCompanyId(@PathVariable Long companyId) {
        return service.findByCompanyId(companyId);
    }
}
