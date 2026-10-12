# ESG Investment Screening Platform

A deliberately simplified, reproducible microservice platform used as a controlled test
environment for measuring resource efficiency (Kubernetes resource limits, container image
optimisation, CI pipeline caching).

**Guides** — architecture, file-by-file tours, request flows and hands-on labs:
- [docs/company-service-guide.md](docs/company-service-guide.md)
- [docs/esg-score-service-guide.md](docs/esg-score-service-guide.md)
- [docs/portfolio-service-guide.md](docs/portfolio-service-guide.md)

## Services

| Service | Port | Database | Role | Status |
|---------|------|----------|------|--------|
| company-service | 8081 | `company_db` | company profiles + raw ESG data, CRUD, 20 seed companies | ✅ |
| esg-score-service | 8082 | `score_db` | fetches raw data via REST, calculates scores, publishes `score.updated` | ✅ |
| portfolio-service | 8083 | `portfolio_db` | portfolios with weighted aggregate score, consumes `score.updated` | ✅ |
| report-service | 8084 | — (in memory) | sustainability reports, consumes `score.updated` | planned |

```
company-service ◄── REST ── esg-score-service ── score.updated ──► RabbitMQ exchange "esg.events"
                                   ▲                                 ├──► portfolio-service ─┐
                                   └──── REST (initial scores) ──────┼───────────────────────┘
                                                                     └──► report-service (planned)
```

## Repository layout

```
esg/
├── pom.xml                      # Maven parent POM (Spring Boot 3.5, Java 21)
├── docker-compose.yml           # local PostgreSQL + RabbitMQ for development
├── docker/postgres/init/        # SQL run once on first Postgres start (creates databases)
├── company-service/             # port 8081
│   ├── Dockerfile               # single-stage variant
│   ├── Dockerfile.multistage    # multi-stage variant
│   └── src/main/resources/db/migration/   # Flyway: schema (V1) and 20 seed companies (V2)
├── esg-score-service/           # port 8082
│   ├── Dockerfile, Dockerfile.multistage
│   └── src/main/java/com/esg/score/scoring/   # pure-Java scoring formula
├── portfolio-service/           # port 8083
│   ├── Dockerfile, Dockerfile.multistage
│   └── src/main/resources/db/migration/   # Flyway: schema (V1) and 3 seed portfolios (V2)
└── docs/                        # guides
```

## Prerequisites

- Java 21 or newer (`java -version`)
- Maven 3.9+ (`mvn -v`)
- Docker Desktop, **running** (`docker info` must not show an error)

## Running locally

All commands are run from the repository root.

```powershell
docker compose up -d                                          # Postgres + RabbitMQ
mvn package -DskipTests                                       # all services

# one terminal per service
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar
java -jar esg-score-service/target/esg-score-service-0.0.1-SNAPSHOT.jar
java -jar portfolio-service/target/portfolio-service-0.0.1-SNAPSHOT.jar
```

Stop a service with `Ctrl+C`. RabbitMQ's management UI: <http://localhost:15672> (`esg` / `esg`).

## Verifying the setup

### Check 1 — infrastructure is healthy

```
docker compose ps
```

Both `esg-postgres` and `esg-rabbitmq` should read `Up ... (healthy)`. Right after starting it
may show `(health: starting)` for a few seconds — run the command again.

If you see `error during connect ... dockerDesktopLinuxEngine`, Docker Desktop is not running.

### Check 2 — the databases exist

```
docker exec -it esg-postgres psql -U esg -c "\l"
```

`company_db`, `score_db` and `portfolio_db` must appear in the `Name` column. If one is missing, the init
script did not run (it only runs on an **empty** volume). Reset the volume:

```
docker compose down -v
docker compose up -d
```

### Check 3 — the services connect and start

Each service's log should contain, in this order:

```
HikariPool-1 - Start completed.                                 # connected to Postgres
Successfully applied N migrations  /  Schema "public" is up to date   # Flyway
Tomcat started on port 808X (http)
Started ...Application in X seconds
```

If the database is unreachable you will instead see `Connection refused` and
`Application run failed`. The startup log should contain no `WARN` lines.

### Check 4 — company-service has its seed data

```
docker exec -it esg-postgres psql -U esg -d company_db -c "select sector, count(*) from companies group by sector"
```

Expected: 5 per sector, 20 in total.

### Check 5 — scores are calculated and published

```powershell
Invoke-RestMethod http://localhost:8082/api/scores -Method Post | Format-Table companyId, companyName, total
```

20 rows; Voltara Renewables (id 6) = 92.6. In the RabbitMQ UI, exchange `esg.events` shows
a spike in its message rate.

### Check 6 — portfolios were updated by the events

```powershell
Invoke-RestMethod http://localhost:8083/api/portfolios | Format-Table id, name, aggregateScore
```

On a fresh database: Sustainable Leaders 94.75, Balanced Mix 69.04, Heavy Industry 20.99.

## Running the tests

| Command | Runs | Needs Docker |
|---------|------|--------------|
| `mvn -pl <service> -am test` | unit + web-slice tests (`*Test.java`) | no |
| `mvn -pl <service> -am verify` | the above **plus** integration tests (`*IT.java`) | yes |
| `mvn verify` | everything, all services | yes |

| Service | `test` | `verify` adds |
|---------|--------|---------------|
| company-service | 25 | 9 (Postgres) |
| esg-score-service | 44 | 4 (Postgres + RabbitMQ) |
| portfolio-service | 31 | 5 (Postgres + RabbitMQ) |

Integration tests start their own throwaway containers via Testcontainers — they do **not**
use (or touch) the docker-compose infrastructure, so they can run while the apps are running.

## company-service REST API

Base URL: `http://localhost:8081/api/companies`

| Method   | Path                             | Success                   | Errors        |
|----------|----------------------------------|---------------------------|---------------|
| `GET`    | `/api/companies`                 | 200 + list (ordered by id)| —             |
| `GET`    | `/api/companies?sector=ENERGY`   | 200 + filtered list       | 400           |
| `GET`    | `/api/companies/{id}`            | 200                       | 404           |
| `POST`   | `/api/companies`                 | 201 + `Location` header   | 400, 409      |
| `PUT`    | `/api/companies/{id}`            | 200 (replaces all fields) | 400, 404, 409 |
| `DELETE` | `/api/companies/{id}`            | 204                       | 404           |

Errors are returned as `application/problem+json` (RFC 9457). Validation errors list the
offending fields under `errors`:

```json
{"title":"Bad Request","status":400,"detail":"Validation failed",
 "errors":{"renewableEnergyPct":"must be less than or equal to 100"}}
```

### Trying it out (PowerShell)

> In Windows PowerShell 5.1, `curl` is an alias for `Invoke-WebRequest` and does not accept
> curl options. Use `Invoke-RestMethod` as below, or call `curl.exe` explicitly.

```powershell
$u = "http://localhost:8081/api/companies"

# list all / filter by sector / get one
Invoke-RestMethod $u | Format-Table id, name, sector
Invoke-RestMethod "${u}?sector=FINANCE" | Format-Table id, name   # ${u}: "$u?" would be read as a variable named "u?"
Invoke-RestMethod "$u/6"

# create
$body = @{
  name = "Test Co"; sector = "FINANCE"; co2Emissions = 10; renewableEnergyPct = 50
  waterConsumption = 100; boardDiversityPct = 30; corruptionIncidents = 0; transparencyScore = 70
} | ConvertTo-Json
$created = Invoke-RestMethod $u -Method Post -ContentType "application/json" -Body $body
$created.id

# update (PUT replaces all fields) and delete
Invoke-RestMethod "$u/$($created.id)" -Method Put -ContentType "application/json" -Body $body
Invoke-RestMethod "$u/$($created.id)" -Method Delete
```

Error responses make `Invoke-RestMethod` throw; the status code is shown in the red error
text. To see the body of an error, use `curl.exe -s http://localhost:8081/api/companies/999`.

## esg-score-service REST API

Base URL: `http://localhost:8082/api/scores`

| Method | Path | Effect | Success | Errors |
|--------|------|--------|---------|--------|
| `POST` | `/api/scores/{companyId}` | fetch → calculate → store → publish `score.updated` | 200 | 404, 503 |
| `POST` | `/api/scores` | the same for **all** companies (one event each) | 200 + list | 503 |
| `GET`  | `/api/scores` | stored scores, ordered by company id | 200 | — |
| `GET`  | `/api/scores/{companyId}` | stored score | 200 | 404 (not calculated yet) |

503 means a dependency is down: company-service (nothing stored) or RabbitMQ (score stored,
event not published).

```powershell
Invoke-RestMethod http://localhost:8082/api/scores/6 -Method Post
```

```json
{"companyId":6,"companyName":"Voltara Renewables","environmental":96.0,"social":92.0,
 "governance":88.0,"total":92.6,"calculatedAt":"2026-10-09T13:20:26.956Z"}
```

### Scoring formula

Each raw value is normalised to 0–100 on its fixed range (inverted where lower is better),
then averaged per pillar and weighted:

```
E = avg(100 − co2/10, renewable%, 100 − water/50)
S = avg(min(diversity% × 2, 100), 100 − incidents × 10)
G = transparency
Total = E × 0.40 + S × 0.35 + G × 0.25          (all rounded to 2 decimals)
```

### Event `score.updated`

| | |
|-|-|
| Exchange | `esg.events` (topic, durable) |
| Routing key | `score.updated` |
| Header | `__TypeId__: scoreUpdated` |
| Payload | `{"companyId":6,"companyName":"Voltara Renewables","totalScore":92.6,"calculatedAt":"2026-10-09T13:21:35.243Z"}` |

Consumers declare their **own** queue and bind it to `score.updated` — every bound queue
receives every event (fan-out).

| Queue | Owner | Purpose |
|-------|-------|---------|
| `portfolio-service.score-updated` | portfolio-service | durable; events wait here while the service is down |
| `portfolio-service.score-updated.dlq` | portfolio-service | messages that failed 3 attempts (or weren't valid JSON) |

## portfolio-service REST API

Base URL: `http://localhost:8083/api/portfolios`

| Method   | Path                    | Success                   | Errors        |
|----------|-------------------------|---------------------------|---------------|
| `GET`    | `/api/portfolios`       | 200 + list (ordered by id)| —             |
| `GET`    | `/api/portfolios/{id}`  | 200                       | 404           |
| `POST`   | `/api/portfolios`       | 201 + `Location` header   | 400, 409      |
| `PUT`    | `/api/portfolios/{id}`  | 200 (replaces name + holdings) | 400, 404, 409 |
| `DELETE` | `/api/portfolios/{id}`  | 204                       | 404           |

```powershell
$body = @{ name = "Mixed Energy"; holdings = @(
  @{ companyId = 6; weight = 0.5 }, @{ companyId = 10; weight = 0.5 }) } | ConvertTo-Json
Invoke-RestMethod http://localhost:8083/api/portfolios -Method Post -ContentType "application/json" -Body $body
```

```json
{"id":4,"name":"Mixed Energy","aggregateScore":56.82,"holdings":[
  {"companyId":6,"companyName":"Voltara Renewables","weight":0.5,"esgScore":92.6,"scoreCalculatedAt":"..."},
  {"companyId":10,"companyName":"Carbonex Fuels","weight":0.5,"esgScore":21.03,"scoreCalculatedAt":"..."}]}
```

- **Aggregate** = Σ(weight × score) / Σ weight over holdings that have a score (`null` until one does).
- **Rules:** weight in (0, 1]; weights sum ≤ 1; each company at most once; name unique (409).
- Current scores are fetched from esg-score-service on create/update; afterwards
  `score.updated` events keep them current. Older events are ignored.

## Health and metrics (Actuator)

Both services expose:

| Endpoint                       | Purpose                                              |
|--------------------------------|------------------------------------------------------|
| `/actuator/health`             | overall status (`{"status":"UP"}`)                   |
| `/actuator/health/liveness`    | Kubernetes liveness probe — "is the JVM alive?"      |
| `/actuator/health/readiness`   | Kubernetes readiness probe — "can it take traffic?"  |
| `/actuator/prometheus`         | all metrics in Prometheus text format                |

Every metric carries the label `application="<service name>"`. Metrics relevant to the
experiments:

| Metric                                  | Used for                         |
|-----------------------------------------|----------------------------------|
| `jvm_memory_used_bytes{area="heap"}`    | JVM heap behaviour per image variant |
| `jvm_memory_max_bytes{area="heap"}`     | effect of `-XX:MaxRAMPercentage` |
| `process_cpu_usage`                     | CPU per pod                      |
| `http_server_requests_seconds_*`        | request rate and latency (p95/p99) under load |
| `http_client_requests_seconds_*`        | esg-score-service → company-service calls |
| `esg_scores_calculated_total`           | score calculations (esg-score-service) |
| `portfolio_score_events_total`          | `score.updated` events processed (portfolio-service) |
| `portfolio_aggregate_recalculations_total` | portfolios changed by those events |
| `hikaricp_connections_active`           | DB connection pool usage         |
| `application_ready_time_seconds`        | startup time                     |

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health/readiness

$metrics = (Invoke-WebRequest http://localhost:8081/actuator/prometheus -UseBasicParsing).Content
$metrics -split "`n" | Select-String '^jvm_memory_used_bytes.*area="heap"' | ForEach-Object { $_.Line }
```

## Docker images

Two variants per service, both built **from the repository root**:

```powershell
docker build -f company-service/Dockerfile              -t company-service:single   .
docker build -f company-service/Dockerfile.multistage   -t company-service:multi    .
docker build -f esg-score-service/Dockerfile            -t esg-score-service:single .
docker build -f esg-score-service/Dockerfile.multistage -t esg-score-service:multi  .
docker build -f portfolio-service/Dockerfile            -t portfolio-service:single .
docker build -f portfolio-service/Dockerfile.multistage -t portfolio-service:multi  .
```

Inside the Dockerfiles each module is built with `mvn -f <service>/pom.xml`: the parent POM
is used for inheritance only, so the other modules don't need to be in the build context.

| Variant | Contents | JVM flags |
|---------|----------|-----------|
| `Dockerfile` (single-stage) | Ubuntu + full JDK 21 + Maven + Maven repo + sources + JAR, runs as root | none (defaults) |
| `Dockerfile.multistage` | Alpine + JRE 21 + JAR only, runs as user `app` | `JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75"` |

Measured (Docker 28, containerd image store):

| | company | score | portfolio |
|-|---------|-------|-----------|
| Compressed, single → multi (what `docker pull` transfers) | 404 → 127 MB | 406 → 128 MB | 408 → 129 MB |
| Unpacked, single → multi (sum of layers in `docker history`) | ~755 → ~272 MB | ~757 → ~273 MB | ~760 → ~274 MB |
| Max heap with `--memory=512m`, single → multi | 128 → 384 MB | 128 → 384 MB | 128 → 384 MB |
| Container memory after startup (multi, 512 MB limit) | ~224 MB | ~233 MB | ~272 MB |

> **Measure size consistently.** With the containerd image store, `docker image ls` adds the
> compressed and unpacked sizes together (e.g. 1.16 GB / 399 MB). Use the compressed size
> for pull-time comparisons: `docker image inspect company-service:multi --format '{{.Size}}'`

Run the services as containers against the compose infrastructure. Inside Docker, other
containers are reached by **container name**, not `localhost`:

```powershell
docker run -d --name cs --network esg_default --memory=512m -p 18081:8081 `
  -e DB_URL=jdbc:postgresql://esg-postgres:5432/company_db company-service:multi

docker run -d --name ss --network esg_default --memory=512m -p 18082:8082 `
  -e DB_URL=jdbc:postgresql://esg-postgres:5432/score_db `
  -e RABBITMQ_HOST=esg-rabbitmq -e COMPANY_SERVICE_URL=http://cs:8081 esg-score-service:multi

docker run -d --name ps --network esg_default --memory=512m -p 18083:8083 `
  -e DB_URL=jdbc:postgresql://esg-postgres:5432/portfolio_db `
  -e RABBITMQ_HOST=esg-rabbitmq -e SCORE_SERVICE_URL=http://ss:8082 portfolio-service:multi

docker rm -f cs ss ps   # stop and remove
```

Switch the JVM flags off for the experiment with `-e JAVA_OPTS=`.

## Seed data

`company-service/src/main/resources/db/migration/V2__seed_companies.sql` inserts 20 fictional
companies — 5 per sector, spread over the rating bands (5 Excellent, 5 Good, 5 Fair, 5 Poor).

| Field                  | Unit                              | Range  | Better |
|------------------------|-----------------------------------|--------|--------|
| `co2_emissions`        | tonnes CO₂ per EUR 1M revenue     | 0–1000 | lower  |
| `renewable_energy_pct` | % of energy from renewables       | 0–100  | higher |
| `water_consumption`    | m³ per EUR 1M revenue             | 0–5000 | lower  |
| `board_diversity_pct`  | % underrepresented board members  | 0–100  | higher |
| `corruption_incidents` | confirmed cases in last 3 years   | 0–10   | lower  |
| `transparency_score`   | disclosure quality rating         | 0–100  | higher |

**Before every experiment run**, reset to the known state (company ids 1–20, no scores,
3 unscored seed portfolios, empty queues):

```
docker compose down -v
docker compose up -d
```

Never edit a migration that has already been applied — Flyway stores a checksum and refuses
to start if it changes. Add a new `V3__...sql` file instead.

## Configuration

Every setting can be overridden with an environment variable; the defaults work locally.

| Variable | Service | Default |
|----------|---------|---------|
| `DB_URL` | all | `jdbc:postgresql://localhost:5432/<company_db, score_db, portfolio_db>` |
| `DB_USERNAME`, `DB_PASSWORD` | all | `esg` / `esg` |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | score, portfolio | `localhost` / `5672` |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | score, portfolio | `esg` / `esg` |
| `COMPANY_SERVICE_URL` | esg-score-service | `http://localhost:8081` |
| `SCORE_SERVICE_URL` | portfolio-service | `http://localhost:8082` |
| `JAVA_OPTS` | multi-stage images | `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75` |

If port 5432 is already taken by a local Postgres installation, change the mapping in
`docker-compose.yml` to `"5433:5432"` and set `DB_URL` accordingly, e.g.
`$env:DB_URL = "jdbc:postgresql://localhost:5433/company_db"`.

## Stopping

| Command                  | Effect                                              |
|--------------------------|-----------------------------------------------------|
| `docker compose stop`    | stops Postgres + RabbitMQ, keeps containers and data |
| `docker compose down`    | removes the containers, keeps data                  |
| `docker compose down -v` | removes containers **and data** (full reset)        |
