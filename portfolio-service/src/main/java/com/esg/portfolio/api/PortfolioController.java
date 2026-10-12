package com.esg.portfolio.api;

import com.esg.portfolio.domain.Portfolio;
import com.esg.portfolio.domain.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {

    private final PortfolioService service;

    public PortfolioController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping
    public List<PortfolioResponse> findAll() {
        return service.findAll().stream().map(PortfolioResponse::from).toList();
    }

    @GetMapping("/{id}")
    public PortfolioResponse findById(@PathVariable Long id) {
        return PortfolioResponse.from(service.findById(id));
    }

    @PostMapping
    public ResponseEntity<PortfolioResponse> create(@Valid @RequestBody PortfolioRequest request) {
        Portfolio created = service.create(request.name(), request.toHoldings());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(PortfolioResponse.from(created));
    }

    @PutMapping("/{id}")
    public PortfolioResponse update(@PathVariable Long id, @Valid @RequestBody PortfolioRequest request) {
        return PortfolioResponse.from(service.update(id, request.name(), request.toHoldings()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
