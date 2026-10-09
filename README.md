# ESG Investment Screening Platform

A deliberately simplified, reproducible microservice platform used as a controlled test
environment for measuring resource efficiency (Kubernetes resource limits, container image
optimisation, CI pipeline caching).

## Repository layout

```
esg/
├── pom.xml                      # Maven parent POM (Spring Boot 3.5, Java 21)
├── docker-compose.yml           # local PostgreSQL for development
├── docker/postgres/init/        # SQL run once on first Postgres start (creates databases)
└── company-service/             # port 8081 — company profiles + raw ESG data
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
