package com.esg.company;

import com.esg.company.api.CompanyResponse;
import com.esg.company.domain.Company;
import com.esg.company.domain.CompanyRepository;
import com.esg.company.domain.Sector;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack tests: real HTTP server on a random port, real Postgres in Docker (Testcontainers),
 * real Flyway migrations. Nothing is mocked.
 */
@Testcontainers
@ActiveProfiles("it")
@AutoConfigureObservability(tracing = false) // Spring Boot disables metrics export in tests unless asked
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompanyApiIT {

    /** Same image as docker-compose.yml. @ServiceConnection points the datasource at this container. */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CompanyRepository repository;

    /** Reset to the seeded state before every test, exactly like `docker compose down -v` before an experiment. */
    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
    }

    private static Map<String, Object> body(String name) {
        return Map.of(
                "name", name,
                "sector", "FINANCE",
                "co2Emissions", 10,
                "renewableEnergyPct", 50,
                "waterConsumption", 100,
                "boardDiversityPct", 30,
                "corruptionIncidents", 0,
                "transparencyScore", 70);
    }

    @Test
    void seedDataContainsTwentyCompaniesWithStableIds() {
        CompanyResponse[] all = rest.getForObject("/api/companies", CompanyResponse[].class);

        assertThat(all).hasSize(20);
        assertThat(all).extracting(CompanyResponse::id)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
                        11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
        assertThat(all[0].name()).isEqualTo("Lumina Cloud Systems");
        assertThat(all[19].name()).isEqualTo("Ashgrove Cement");
    }

    @Test
    void seedDataHasFiveCompaniesPerSector() {
        CompanyResponse[] all = rest.getForObject("/api/companies", CompanyResponse[].class);

        Map<Sector, Long> perSector = Arrays.stream(all)
                .collect(Collectors.groupingBy(CompanyResponse::sector, Collectors.counting()));
        assertThat(perSector).containsOnly(
                Map.entry(Sector.TECHNOLOGY, 5L),
                Map.entry(Sector.ENERGY, 5L),
                Map.entry(Sector.FINANCE, 5L),
                Map.entry(Sector.MANUFACTURING, 5L));
    }

    @Test
    void sectorFilterQueriesTheDatabase() {
        CompanyResponse[] energy = rest.getForObject("/api/companies?sector=ENERGY", CompanyResponse[].class);

        assertThat(energy).extracting(CompanyResponse::id).containsExactly(6L, 7L, 8L, 9L, 10L);
        assertThat(energy).allSatisfy(c -> assertThat(c.sector()).isEqualTo(Sector.ENERGY));
    }

    @Test
    void fullCrudRoundTrip() {
        // create
        ResponseEntity<CompanyResponse> created =
                rest.postForEntity("/api/companies", body("Round Trip Co"), CompanyResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().id()).isEqualTo(21L);
        URI location = created.getHeaders().getLocation();
        assertThat(location).isNotNull();

        // read back via the Location header
        CompanyResponse fetched = rest.getForObject(location, CompanyResponse.class);
        assertThat(fetched.name()).isEqualTo("Round Trip Co");
        assertThat(fetched.sector()).isEqualTo(Sector.FINANCE);

        // update
        ResponseEntity<CompanyResponse> updated = rest.exchange(location, HttpMethod.PUT,
                new HttpEntity<>(body("Round Trip Co Renamed")), CompanyResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repository.findById(21L)).get()
                .extracting(Company::getName).isEqualTo("Round Trip Co Renamed");

        // delete
        ResponseEntity<Void> deleted = rest.exchange(location, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rest.getForEntity(location, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(repository.count()).isEqualTo(20);
    }

    @Test
    void duplicateSeedNameIsRejectedWith409() {
        ResponseEntity<String> response =
                rest.postForEntity("/api/companies", body("Alderpoint Bank"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void databaseCheckConstraintRejectsOutOfRangeValues() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO companies (name, sector, co2_emissions, renewable_energy_pct, water_consumption,
                                       board_diversity_pct, corruption_incidents, transparency_score)
                VALUES ('Bypass Co', 'ENERGY', 10, 140, 10, 10, 0, 10)
                """))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("companies_renewable_energy_pct_check");
    }

    @Test
    void healthAndKubernetesProbesReportUp() {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            ResponseEntity<String> response = rest.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as(path).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void prometheusEndpointExposesMetricsNeededForExperiments() {
        rest.getForObject("/api/companies/1", String.class); // produce at least one HTTP metric

        String metrics = rest.getForObject("/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("jvm_memory_used_bytes{")            // JVM heap experiment
                .contains("process_cpu_usage{")                // CPU
                .contains("hikaricp_connections_active{")      // DB connection pool
                .contains("http_server_requests_seconds_bucket{") // latency histogram
                .contains("application=\"company-service\"");  // per-service label for Grafana
    }

    @Test
    void databaseUniqueConstraintRejectsDuplicateNames() {
        Company duplicate = new Company("Ashgrove Cement", Sector.MANUFACTURING, 10, 10, 10, 10, 0, 10);

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
