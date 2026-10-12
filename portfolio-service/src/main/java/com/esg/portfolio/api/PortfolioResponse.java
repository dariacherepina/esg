package com.esg.portfolio.api;

import com.esg.portfolio.domain.Holding;
import com.esg.portfolio.domain.Portfolio;

import java.time.Instant;
import java.util.List;

/** aggregateScore, companyName, esgScore and scoreCalculatedAt are null until a score is known. */
public record PortfolioResponse(
        Long id,
        String name,
        Double aggregateScore,
        List<HoldingResponse> holdings
) {

    public record HoldingResponse(
            Long companyId,
            String companyName,
            double weight,
            Double esgScore,
            Instant scoreCalculatedAt
    ) {
        static HoldingResponse from(Holding holding) {
            return new HoldingResponse(holding.getCompanyId(), holding.getCompanyName(), holding.getWeight(),
                    holding.getEsgScore(), holding.getScoreCalculatedAt());
        }
    }

    public static PortfolioResponse from(Portfolio portfolio) {
        return new PortfolioResponse(portfolio.getId(), portfolio.getName(), portfolio.getAggregateScore(),
                portfolio.getHoldings().stream().map(HoldingResponse::from).toList());
    }
}
