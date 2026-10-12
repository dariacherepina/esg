package com.esg.portfolio.api;

import com.esg.portfolio.domain.Holding;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body of POST and PUT. Rules spanning several holdings (unique company, weights sum <= 1) live in Portfolio. */
public record PortfolioRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty List<@Valid @NotNull HoldingRequest> holdings
) {

    public record HoldingRequest(
            @NotNull Long companyId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") Double weight
    ) {
    }

    public List<Holding> toHoldings() {
        return holdings.stream()
                .map(h -> new Holding(h.companyId(), h.weight()))
                .toList();
    }
}
