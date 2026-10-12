# portfolio-service — How It Works

portfolio-service manages investment portfolios and keeps their ESG score current **without
ever being asked to** — it listens to `score.updated` events. This guide builds on
[company-service-guide.md](company-service-guide.md) and
[esg-score-service-guide.md](esg-score-service-guide.md).

All commands run from the repository root in **PowerShell**.

---

## Part 1 — The big picture

```
                         esg-score-service
                     POST /api/scores → publishes
                                 │ score.updated
                                 ▼
                       exchange "esg.events"
                                 │ binding "score.updated"
                                 ▼
               queue "portfolio-service.score-updated"  (durable)
                                 │                 │ rejected after 3 attempts
                                 ▼                 ▼
┌────────────────────────────────────────┐  queue "...score-updated.dlq"
│ portfolio-service :8083                │
│  ScoreUpdatedListener                  │
│    → find portfolios holding company   │
│    → update holding score              │◄── GET /api/scores/{id}  (only when a portfolio
│    → recalculate aggregate             │     is created/updated: initial scores)
│  portfolio_db                          │
└────────────────────────────────────────┘
```

**Asynchronous consumer** is the key idea. Nobody calls portfolio-service when a score
changes. esg-score-service doesn't even know it exists. The event is delivered to
portfolio-service's own queue, and the listener processes it whenever it can — if
portfolio-service is down, events wait in the queue.

---

## Part 2 — The data model

```
portfolios                         holdings
┌────┬─────────────────────┬──────────────┐   ┌────┬──────────────┬────────────┬─────────────────┬────────┬───────────┬─────────────────────┐
│ id │ name                │ aggregate_   │   │ id │ portfolio_id │ company_id │ company_name    │ weight │ esg_score │ score_calculated_at │
│    │                     │ score        │   │    │ (FK, cascade)│ (no FK)    │ (local copy)    │ (0..1] │ (copy)    │ (for ordering)      │
├────┼─────────────────────┼──────────────┤   ├────┼──────────────┼────────────┼─────────────────┼────────┼───────────┼─────────────────────┤
│ 1  │ Sustainable Leaders │ 94.75        │◄──│ .. │ 1            │ 6          │ Voltara Ren...  │ 0.30   │ 92.6      │ 2026-10-12T12:37:48 │
└────┴─────────────────────┴──────────────┘   └────┴──────────────┴────────────┴─────────────────┴────────┴───────────┴─────────────────────┘
```

* **`portfolio_id` has a foreign key** (same database, same service) with `ON DELETE CASCADE`:
  deleting a portfolio deletes its holdings.
* **`company_id` has no foreign key** — companies live in another service's database.
* **`company_name` and `esg_score` are copies.** This service keeps its own copy of the data
  it needs, updated by events. It can answer `GET /api/portfolios` even when company-service
  and esg-score-service are both down. The price: the copy can be briefly out of date
  ("eventual consistency").

### Aggregate score

```
aggregate = Σ (weight × score)  /  Σ weight          (only holdings that have a score)
```

Dividing by the sum of weights means the weights don't have to add up to exactly 1, and a
holding without a score yet doesn't drag the result towards 0.

**Example — Sustainable Leaders:** 0.40 × 95.06 + 0.30 × 92.60 + 0.30 × 96.49 = 94.751 → **94.75**

### Rules

| Rule | Checked by | Response |
|------|-----------|----------|
| name not blank, ≤ 100 chars | Bean Validation on `PortfolioRequest` | 400 |
| at least one holding | `@NotEmpty` | 400 |
| weight in (0, 1] | `@DecimalMin(inclusive = false)`, `@DecimalMax` + DB `CHECK` | 400 |
| a company at most once | `Portfolio.validate` + DB `UNIQUE (portfolio_id, company_id)` | 400 |
| weights sum ≤ 1 | `Portfolio.validate` (tolerance 1e-9 for floating point) | 400 |
| name unique | service check + DB `UNIQUE` | 409 |

The floating-point tolerance matters: in `double` arithmetic, `0.1 + 0.2 + 0.7` is
`1.0000000000000002`. Without the tolerance a perfectly valid portfolio would be rejected
(there's a test for exactly this case).

### Seed portfolios

| id | Name | Holdings | Aggregate when scored |
|----|------|----------|-----------------------|
| 1 | Sustainable Leaders | 1 (0.4), 6 (0.3), 11 (0.3) | 94.75 — Excellent |
| 2 | Balanced Mix | 3, 8, 12, 17 (0.25 each) | 69.04 — Good |
| 3 | Heavy Industry | 9 (0.3), 10 (0.3), 20 (0.4) | 20.99 — Poor |

They start **without** scores — `POST /api/scores` on esg-score-service fills them in.

---

## Part 3 — Consuming events reliably

### The queue setup (`MessagingConfig`)

| Element | Why |
|---------|-----|
| Exchange `esg.events` declared here too | Declaring is idempotent. Without it, the binding would fail if esg-score-service had never started. |
| Queue `portfolio-service.score-updated`, **durable** | Survives a RabbitMQ restart; events published while this service is down wait here. |
| Binding with routing key `score.updated` | Gets a copy of every score event — report-service gets its own copy in its own queue. |
| Dead-letter queue `...score-updated.dlq` | Where messages go that could not be processed. |
| Type id `scoreUpdated` → local `ScoreUpdatedEvent` | Own copy of the event class; the JSON shape is the contract, not shared Java code. |

### What happens when processing fails

```
message ──► listener throws ──► retry after 1 s ──► retry after 2 s ──► rejected (no requeue)
                                                                              │
                                    dead-letter exchange "" ──► portfolio-service.score-updated.dlq
```

Configured in `application.yml` (`retry.max-attempts: 3`, `default-requeue-rejected: false`).
Without this, a message that always fails (a "poison message") would be redelivered forever,
blocking the queue and burning CPU. Messages that can't even be parsed as JSON skip the
retries and go straight to the DLQ — retrying wouldn't help.

### Ordering and duplicates

RabbitMQ can deliver a message twice (e.g. after a crash before the ack), and two calculations
for the same company could arrive out of order. `Holding.applyScore` therefore ignores any
event whose `calculatedAt` is **not newer** than the stored one. Result: processing is
**idempotent** — replaying an event changes nothing.

### Transactions and network calls

When a portfolio is created or updated, `PortfolioService` asks esg-score-service for the
current scores — **before** starting the database transaction (`TransactionTemplate`).
Holding a DB connection while waiting on the network would exhaust the connection pool under
load. If esg-score-service is down, the portfolio is still created; scores arrive with the
next event.

---

## Part 4 — Tests

| Test class | What's real | What's fake |
|------------|-------------|-------------|
| `PortfolioTest` (11) | entity logic: aggregate, ordering, validation | nothing |
| `PortfolioServiceTest` (8) | service + entities | repository, score client, transaction |
| `ScoreClientTest` (3) | client, JSON mapping | esg-score-service (`MockRestServiceServer`) |
| `PortfolioControllerTest` (9) | controller, validation, error handler | service |
| `PortfolioServiceIT` (5) | HTTP, Postgres, RabbitMQ, Flyway, listener, DLQ | only the score client |

`PortfolioServiceIT` publishes **raw JSON bytes** with the `scoreUpdated` header — exactly what
esg-score-service sends — instead of using this service's own Java class. That tests the
contract between the services. Because the listener runs asynchronously, assertions use
**Awaitility** (`await().atMost(10 s).untilAsserted(...)`) instead of checking immediately.

---

## Part 5 — Hands-on labs

Prerequisite (one terminal per service):

```powershell
docker compose up -d
mvn -pl company-service,esg-score-service,portfolio-service -am package -DskipTests
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar
java -jar esg-score-service/target/esg-score-service-0.0.1-SNAPSHOT.jar
java -jar portfolio-service/target/portfolio-service-0.0.1-SNAPSHOT.jar
```

### Lab 1 — Watch portfolios update themselves

```powershell
Invoke-RestMethod http://localhost:8083/api/portfolios | Format-Table id, name, aggregateScore
Invoke-RestMethod http://localhost:8082/api/scores -Method Post | Out-Null
Start-Sleep 2
Invoke-RestMethod http://localhost:8083/api/portfolios | Format-Table id, name, aggregateScore
```

**Look for:** the aggregates go from empty to 94.75 / 69.04 / 20.99. You never called
portfolio-service in between — the events did it. (On a fresh database; if scores were
already calculated before, they are filled in already.)

### Lab 2 — Create your own portfolio

```powershell
$body = @{ name = "Mixed Energy"; holdings = @(
  @{ companyId = 6; weight = 0.5 }, @{ companyId = 10; weight = 0.5 }) } | ConvertTo-Json -Depth 3
Invoke-RestMethod http://localhost:8083/api/portfolios -Method Post -ContentType "application/json" -Body $body
```

**Look for:** scores and company names are already filled in — fetched from
esg-score-service at creation.

Try invalid input: weights `0.7` + `0.4`, the same `companyId` twice, `weight = 0`.

### Lab 3 — Events wait while the service is down

1. Stop portfolio-service (Ctrl+C).
2. `Invoke-RestMethod http://localhost:8082/api/scores/6 -Method Post`
3. In the RabbitMQ UI (<http://localhost:15672>, `esg`/`esg`) → **Queues** →
   `portfolio-service.score-updated`: 1 message *Ready*, 0 consumers.
4. Start portfolio-service again → the message is consumed, `scoreCalculatedAt` of company 6
   in portfolio 1 shows the new time.

### Lab 4 — A poison message

In the RabbitMQ UI → **Exchanges** → `esg.events` → *Publish message*:
- Routing key: `score.updated`
- Headers: `__TypeId__` = `scoreUpdated`
- Properties: `content_type` = `application/json`
- Payload: `{not json`

**Look for:** a conversion error in the portfolio-service log and 1 message in
`portfolio-service.score-updated.dlq`. The main queue is not blocked. Purge the DLQ afterwards.

### Lab 5 — Idempotency

Publish the same valid event twice via the UI (payload
`{"companyId":6,"companyName":"Voltara Renewables","totalScore":10,"calculatedAt":"2020-01-01T00:00:00Z"}`).

**Look for:** nothing changes — the date is older than the stored score, so it's ignored.
Change the date to `2030-01-01T00:00:00Z`: now the score of company 6 becomes 10 and every portfolio
holding it drop. Recalculate company 6 afterwards… and notice it **doesn't** recover, because
your fake event is "newer" than the real calculation. Fix with
`docker compose down -v` + restart — a nice illustration of why event timestamps must come
from a trustworthy source.

### Lab 6 — Metrics

```powershell
$m = (Invoke-WebRequest http://localhost:8083/actuator/prometheus -UseBasicParsing).Content
$m -split "`n" | Select-String '^portfolio_' | ForEach-Object { $_.Line }
```

**Look for:** `portfolio_score_events_total` (events processed) vs.
`portfolio_aggregate_recalculations_total` (portfolios actually changed) — 20 events
recalculate only the portfolios that hold those companies.

---

## Part 6 — Self-check questions

1. Why does `holdings.portfolio_id` have a foreign key but `holdings.company_id` doesn't?
2. portfolio-service is down for an hour while scores change. What happens when it restarts?
3. Why is the score fetched from esg-score-service *before* the transaction starts?
4. What would happen without `default-requeue-rejected: false` if a message always fails?
5. Why is the aggregate divided by Σ weight instead of 1?
6. Why does Lab 5's fake future-dated event "stick"?
7. Both portfolio-service and report-service consume `score.updated`. How many queues exist,
   and who declares them?
