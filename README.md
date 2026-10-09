# ESG Investment Screening Platform

A deliberately simplified, reproducible microservice platform used as a controlled test
environment for measuring resource efficiency (Kubernetes resource limits, container image
optimisation, CI pipeline caching).

**New here?** Read [docs/company-service-guide.md](docs/company-service-guide.md) — architecture,
a file-by-file tour, request flow, and hands-on labs.

## Repository layout

```
esg/
├── pom.xml                      # Maven parent POM (Spring Boot 3.5, Java 21)
├── docker-compose.yml           # local PostgreSQL for development
├── docker/postgres/init/        # SQL run once on first Postgres start (creates databases)
└── company-service/             # port 8081 — company profiles + raw ESG data
    └── src/main/resources/db/migration/   # Flyway: schema (V1) and 20 seed companies (V2)
```

## Prerequisites

- Java 21 or newer (`java -version`)
- Maven 3.9+ (`mvn -v`)
- Docker Desktop, **running** (`docker info` must not show an error)

## Running locally

All commands are run from the repository root.

### 1. Start PostgreSQL

```
docker compose up -d
```

### 2. Build and start company-service

```
mvn -pl company-service -am package
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar
```

Stop the service with `Ctrl+C`.

## Verifying the setup

### Check 1 — Postgres container is healthy

```
docker compose ps
```

The `STATUS` column should read `Up ... (healthy)`. Right after starting it may show
`(health: starting)` for a few seconds — run the command again.

If you see `error during connect ... dockerDesktopLinuxEngine`, Docker Desktop is not running.

### Check 2 — `company_db` exists

```
docker exec -it esg-postgres psql -U esg -c "\l"
```

This runs the `psql` client inside the container and lists all databases.
`company_db` must appear in the `Name` column.

If it is missing, the init script did not run (it only runs on an **empty** volume).
Reset the volume and start again:

```
docker compose down -v
docker compose up -d
```

### Check 3 — the app connects to the database

Start the app (see above) and watch the log output for:

```
HikariPool-1 - Start completed.
```

HikariCP is the connection pool; this line means a real connection to Postgres was opened.
If the database is unreachable, you will instead see `Connection refused` and
`APPLICATION FAILED TO START`.

### Check 4 — the app is running on port 8081

The log should end with:

```
Tomcat started on port 8081 (http) with context path '/'
Started CompanyServiceApplication in X seconds
```

Then open <http://localhost:8081> in a browser. A **Whitelabel Error Page (404)** is
expected — the server is running, there are just no endpoints yet.

The startup log should contain no `WARN` lines. On PowerShell you can filter for them:

```
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar | Select-String "WARN"
```

### Check 5 — schema and seed data were created by Flyway

On the **first** start against an empty database the log shows:

```
Successfully applied 2 migrations to schema "public", now at version v2
```

On every later start: `Schema "public" is up to date. No migration necessary.`

Count the seeded companies (expected: 5 per sector, 20 in total):

```
docker exec -it esg-postgres psql -U esg -d company_db -c "select sector, count(*) from companies group by sector"
```

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

**Before every experiment run**, reset to the known state so ids 1–20 and all values are
identical:

```
docker compose down -v
docker compose up -d
```

Never edit a migration that has already been applied — Flyway stores a checksum and refuses
to start if it changes. Add a new `V3__...sql` file instead.

## Configuration

company-service reads its database settings from environment variables, falling back to
local defaults:

| Variable      | Default                                         |
|---------------|-------------------------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/company_db`   |
| `DB_USERNAME` | `esg`                                           |
| `DB_PASSWORD` | `esg`                                           |

If port 5432 is already taken by a local Postgres installation, change the mapping in
`docker-compose.yml` to `"5433:5432"` and start the app with:

```
$env:DB_URL = "jdbc:postgresql://localhost:5433/company_db"
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar
```

## Stopping

| Command                  | Effect                                       |
|--------------------------|----------------------------------------------|
| `docker compose stop`    | stops Postgres, keeps container and data     |
| `docker compose down`    | removes the container, keeps data            |
| `docker compose down -v` | removes container **and data** (full reset)  |
