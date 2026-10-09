# esg-score-service — How It Works

This guide explains esg-score-service: how it turns raw company data into ESG scores, how it
talks to company-service over REST, and how it notifies other services through RabbitMQ.
It builds on [company-service-guide.md](company-service-guide.md) — concepts explained there
(layers, JPA, Flyway, Actuator, Dockerfiles) are not repeated.

All commands run from the repository root in **PowerShell**.

---

## Part 1 — The big picture

```
                 POST /api/scores/6
                        │
                        ▼
┌───────────────────────────────────────────┐   GET /api/companies/6   ┌─────────────────┐
│ esg-score-service :8082                   │ ───────────────────────► │ company-service │
│                                           │ ◄─────────────────────── │ :8081           │
│  1. fetch raw data   (CompanyClient)      │     raw ESG values       └─────────────────┘
│  2. calculate        (EsgScoreCalculator) │
│  3. save             (score_db)           │
│  4. publish event    (ScoreEventPublisher)│
└──────────────────────────┬────────────────┘
                           │ score.updated
                           ▼
              ┌───────────────────────────┐
              │ RabbitMQ exchange         │
              │ "esg.events" (topic)      │
              └─────┬───────────────┬─────┘
      binding       │               │      binding
   "score.updated"  ▼               ▼   "score.updated"
         ┌────────────────┐   ┌────────────────┐
         │ queue of       │   │ queue of       │      (built in later steps)
         │ portfolio-svc  │   │ report-service │
         └────────────────┘   └────────────────┘
```

Two communication styles meet here:

| | REST (to company-service) | RabbitMQ (to portfolio/report) |
|-|---------------------------|--------------------------------|
| Style | **synchronous** — waits for the answer | **asynchronous** — fire and forget |
| Coupling | caller must know the URL; if company-service is down, the call fails | publisher doesn't know who listens; consumers can be down and catch up later |
| Used because | the score *needs* the raw data right now | nobody needs to wait for portfolios/reports to update |

---

## Part 2 — The scoring formula

`EsgScoreCalculator` is a **pure Java class**: no Spring annotations, no database, no network.
Same input → same output, always. That makes it trivially testable and is why it is
registered as a Spring bean in `ClientConfig` instead of carrying `@Component` itself.

**Step 1 — normalise every raw value to 0–100** (min-max on the fixed ranges of company-service):

| Raw value | Range | Sub-score | Direction |
|-----------|-------|-----------|-----------|
| CO₂ emissions | 0–1000 t/€1M | `100 − co2 / 10` | lower is better → inverted |
| Renewable energy | 0–100 % | `renewable %` | already 0–100 |
| Water consumption | 0–5000 m³/€1M | `100 − water / 50` | inverted |
| Board diversity | 0–100 % | `min(diversity % × 2, 100)` | parity (50 %) = 100 |
| Corruption incidents | 0–10 | `100 − incidents × 10` | inverted |
| Transparency | 0–100 | `transparency` | already 0–100 |

Every sub-score is **clamped** to 0–100, so even a value outside the range can't break the result.

**Step 2 — pillar scores** (simple averages):
```
E = (CO₂ + renewables + water) / 3
S = (diversity + integrity) / 2
G = transparency
```

**Step 3 — weighted total:**
```
Total = E × 0.40 + S × 0.35 + G × 0.25
```

All four values are rounded to 2 decimals (`HALF_UP`) so results are identical on every run.

**Worked example — Voltara Renewables** (co2 40, renewables 98, water 300, diversity 42, 0 incidents, transparency 88):
```
E = (96 + 98 + 94) / 3 = 96.00
S = (84 + 100) / 2     = 92.00
G = 88.00
Total = 96×0.40 + 92×0.35 + 88×0.25 = 38.4 + 32.2 + 22.0 = 92.60
```

The 20 seed companies produce 5 Excellent (≥80), 5 Good (≥60), 5 Fair (≥40) and 5 Poor —
`EsgScoreCalculatorTest` pins every one of them to an independently computed value.

---

## Part 3 — File-by-file tour

```
esg-score-service/src/main/java/com/esg/score/
├── EsgScoreServiceApplication.java
├── scoring/                    pure domain logic — no Spring
│   ├── EsgInput.java           raw values (record)
│   ├── EsgScore.java           E, S, G, total (record)
│   └── EsgScoreCalculator.java the formula
├── client/                     talking to company-service
│   ├── CompanyClient.java      RestClient calls + error translation
│   ├── CompanyData.java        JSON shape of a company
│   ├── CompanyNotFoundException.java
│   └── CompanyServiceUnavailableException.java
├── domain/                     storage + orchestration
│   ├── CompanyScore.java       JPA entity, one row per company
│   ├── CompanyScoreRepository.java
│   ├── ScoreService.java       fetch → calculate → save → publish
│   └── ScoreNotFoundException.java
├── messaging/                  RabbitMQ
│   ├── MessagingConfig.java    exchange + JSON converter
│   ├── ScoreEventPublisher.java
│   └── ScoreUpdatedEvent.java  the event (record)
├── config/ClientConfig.java    RestClient (URL, timeouts), Clock, calculator bean
└── api/                        HTTP
    ├── ScoreController.java
    ├── ScoreResponse.java
    └── ApiExceptionHandler.java
```

### Things worth noticing

* **`company_scores` uses the company id as primary key** — not generated. One row per
  company; recalculating overwrites it. There is **no foreign key** to `companies`: that
  table lives in a different database owned by another service. Microservices only share
  data through APIs and events, never through tables.
* **`CompanyData` copies the JSON shape** of company-service's response instead of sharing a
  Java class. Unknown fields are ignored, so company-service can add fields without breaking
  this service.
* **Timeouts** (`ClientConfig`): connect 2 s, read 5 s. Without them, a hanging
  company-service would block request threads here indefinitely — under load, the whole
  service would freeze.
* **`Clock` is injected** instead of calling `Instant.now()` — tests use a fixed time and can
  assert exact timestamps.
* **Order in `ScoreService`: save first, then publish.** If publishing happened before the
  save and the save failed, consumers would act on a score that doesn't exist. The reverse
  failure (saved, but RabbitMQ down) returns 503 and the next calculation publishes again.

---

## Part 4 — RabbitMQ concepts

| Concept | In this project |
|---------|-----------------|
| **Exchange** — receives messages and routes them | `esg.events`, type *topic*, durable |
| **Routing key** — label on each message | `score.updated` |
| **Queue** — stores messages until a consumer takes them | one per consumer (later: portfolio, report) |
| **Binding** — rule "exchange → queue for routing key X" | each consumer binds its own queue to `score.updated` |

**Why does this give fan-out?** The exchange copies a message into *every* queue whose binding
matches. Two queues bound to `score.updated` → each gets its own copy. The publisher doesn't
know or care how many there are.

**Who declares what?** The publisher only declares the exchange (`MessagingConfig`). Queues
belong to consumers. A message published while no queue is bound is simply dropped — that's
fine here, because the score is stored and can be recalculated.

**Message format** — what actually travels over the wire:

```
routing key:   score.updated
content_type:  application/json
delivery_mode: 2                      (persistent: survives a broker restart once queued)
headers:       __TypeId__ = scoreUpdated
payload:       {"companyId":6,"companyName":"Voltara Renewables",
                "totalScore":92.6,"calculatedAt":"2026-10-09T13:21:35.243Z"}
```

`__TypeId__` is a **logical name**, not the Java class name `com.esg.score.messaging.ScoreUpdatedEvent`.
Consumers in other services map `scoreUpdated` to *their own* class, so no Java code is shared.

---

## Part 5 — Error handling

| Situation | Where it's detected | HTTP response |
|-----------|---------------------|---------------|
| Company id unknown | company-service answers 404 → `CompanyNotFoundException` | 404 |
| Score not calculated yet (`GET /api/scores/7`) | `ScoreNotFoundException` | 404 |
| company-service down / slow / 5xx | `CompanyServiceUnavailableException` | 503 |
| RabbitMQ down | `AmqpException` from `RabbitTemplate` | 503 (score is already saved) |

**503 Service Unavailable** (not 500) tells the caller: *this isn't a bug, a dependency is
down — try again later.*

---

## Part 6 — Tests

| Test class | What's real | What's fake |
|------------|-------------|-------------|
| `EsgScoreCalculatorTest` (28) | calculator | nothing — it's pure |
| `ScoreServiceTest` (5) | service + calculator | client, repository, publisher (Mockito) |
| `CompanyClientTest` (4) | client, JSON mapping | company-service (`MockRestServiceServer`) |
| `ScoreControllerTest` (7) | controller, error handler | service |
| `ScoreServiceIT` (4) | HTTP, Postgres, **RabbitMQ**, Flyway | only `CompanyClient` |

`ScoreServiceIT` plays the role of a future consumer: it declares its own queue
`it.score-updated`, binds it to `score.updated`, triggers a calculation over HTTP and then
reads the event from the queue — proving the whole publish path including JSON and the
logical type id.

```powershell
mvn -pl esg-score-service -am test      # 44 tests, no Docker
mvn -pl esg-score-service -am verify    # + 4 integration tests (Postgres + RabbitMQ containers)
```

---

## Part 7 — Hands-on labs

Prerequisite:

```powershell
docker compose up -d
mvn -pl company-service,esg-score-service -am package -DskipTests
```

Run each service in its own terminal:

```powershell
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar
java -jar esg-score-service/target/esg-score-service-0.0.1-SNAPSHOT.jar
```

### Lab 1 — Calculate scores

```powershell
Invoke-RestMethod http://localhost:8082/api/scores/6 -Method Post
Invoke-RestMethod http://localhost:8082/api/scores -Method Post | Format-Table companyId, companyName, total
Invoke-RestMethod http://localhost:8082/api/scores/6
```

**Look for:** Voltara Renewables = 92.6, exactly as in the worked example. Totals ordered by id.

### Lab 2 — The RabbitMQ management UI

Open <http://localhost:15672> (user `esg`, password `esg`).

1. **Exchanges** → `esg.events`: type *topic*, durable. Its "Message rates" graph shows a
   spike for every calculation.
2. **Queues** → *Add a new queue*: name `lab.score-updated` → *Add queue*.
3. Click the queue → **Bindings** → *From exchange* `esg.events`, routing key
   `score.updated` → *Bind*.
4. Run `Invoke-RestMethod http://localhost:8082/api/scores -Method Post` again.
5. The queue now shows **20 Ready** messages. Under **Get messages** → *Get Message(s)* you
   see the routing key, the `__TypeId__` header and the JSON payload.

**Look for:** you just played the role of portfolio-service. A second queue bound the same
way would receive its own 20 copies — that is the fan-out. Delete the queue afterwards.

### Lab 3 — Watch the REST call to company-service

```powershell
$m = (Invoke-WebRequest http://localhost:8082/actuator/prometheus -UseBasicParsing).Content
$m -split "`n" | Select-String '^(http_client_requests_seconds_count|esg_scores_calculated_total)' | ForEach-Object { $_.Line }
```

**Look for:** `http_client_requests_seconds_count{...uri="/api/companies/{id}"...}` — every
outgoing call is measured automatically because the RestClient was built from Spring Boot's
builder. `esg_scores_calculated_total` is the custom counter from `ScoreService`.

### Lab 4 — company-service goes down

Stop company-service (Ctrl+C in its terminal), then:

```powershell
curl.exe -s -X POST http://localhost:8082/api/scores/6
Invoke-RestMethod http://localhost:8082/api/scores/6
```

**Look for:** the POST returns **503** with `company-service is unavailable`. The GET still
works — the last stored score is served from score_db. Start company-service again.

### Lab 5 — RabbitMQ goes down

```powershell
docker compose stop rabbitmq
curl.exe -s -X POST http://localhost:8082/api/scores/6
docker compose start rabbitmq
```

**Look for:** **503** "Message broker unavailable: score was saved but score.updated could not
be published". Check `GET /api/scores/6` — `calculatedAt` was updated, proving the save
happened before the publish. After RabbitMQ is back, the next POST succeeds again
(Spring reconnects automatically).

### Lab 6 — Change the formula and watch the tests

In `EsgScoreCalculator`, change `WEIGHT_GOVERNANCE` from `0.25` to `0.30`. Run
`mvn -pl esg-score-service -am test`.

**Look for:** `totalAppliesPillarWeights` and all 20 seed-company tests fail with the old vs.
new value. The weights in the project description are now enforced by tests. **Undo.**

---

## Part 8 — Self-check questions

1. Why is there no foreign key from `company_scores.company_id` to `companies.id`?
2. What happens to a `score.updated` event published while no queue is bound?
3. Why does `ScoreService` save before it publishes — and what is the cost of that order?
4. Why are the RestClient timeouts important under load?
5. How many copies of each event will exist once portfolio-service and report-service run?
6. Why does `__TypeId__` contain `scoreUpdated` instead of the Java class name?
7. company-service is down. Which endpoints of esg-score-service still work?
