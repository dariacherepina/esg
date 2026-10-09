package com.esg.score.api;

import com.esg.score.client.CompanyNotFoundException;
import com.esg.score.client.CompanyServiceUnavailableException;
import com.esg.score.domain.ScoreNotFoundException;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Translates exceptions into RFC 9457 problem responses (application/problem+json). */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler({CompanyNotFoundException.class, ScoreNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /** A dependency (company-service or RabbitMQ) is down: 503 tells the caller to retry later. */
    @ExceptionHandler(CompanyServiceUnavailableException.class)
    public ProblemDetail handleCompanyServiceUnavailable(CompanyServiceUnavailableException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(AmqpException.class)
    public ProblemDetail handleBrokerUnavailable(AmqpException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Message broker unavailable: score was saved but score.updated could not be published");
    }
}
