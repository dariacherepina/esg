package com.esg.report.messaging;

import com.esg.report.report.ReportService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ScoreUpdatedListener {

    private final ReportService reportService;

    public ScoreUpdatedListener(ReportService reportService) {
        this.reportService = reportService;
    }

    @RabbitListener(queues = MessagingConfig.QUEUE)
    public void onScoreUpdated(ScoreUpdatedEvent event) {
        reportService.onScoreUpdated(event.companyId(), event.companyName(), event.totalScore(), event.calculatedAt());
    }
}
