package com.esg.report.config;

import com.esg.report.report.ReportGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** Injected instead of calling Instant.now() directly, so tests can use a fixed time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** The generator has no Spring annotations itself; it is registered as a bean here. */
    @Bean
    public ReportGenerator reportGenerator() {
        return new ReportGenerator();
    }
}
