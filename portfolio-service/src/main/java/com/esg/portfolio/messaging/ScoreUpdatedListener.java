package com.esg.portfolio.messaging;

import com.esg.portfolio.domain.PortfolioService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ScoreUpdatedListener {

    private final PortfolioService portfolioService;

    public ScoreUpdatedListener(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /** Called by Spring for every message in this service's queue. An exception triggers retry, then DLQ. */
    @RabbitListener(queues = MessagingConfig.QUEUE)
    public void onScoreUpdated(ScoreUpdatedEvent event) {
        portfolioService.applyScoreUpdate(event.companyId(), event.companyName(),
                event.totalScore(), event.calculatedAt());
    }
}
