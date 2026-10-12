# report-service — How It Works

report-service turns every `score.updated` event into a sustainability report with a rating
and recommendations. It has **no database** — it is the lightest service in the platform and
the contrast point in the resource measurements. This guide builds on the guides for
[company-service](company-service-guide.md), [esg-score-service](esg-score-service-guide.md)
and [portfolio-service](portfolio-service-guide.md).

All commands run from the repository root in **PowerShell**.

---

## Part 1 — The big picture

```
esg-score-service ── score.updated ──► exchange "esg.events"
                                         ├──► portfolio-service.score-updated ──► portfolio-service
                                         └──► report-service.score-updated    ──► report-service :8084
                                                     │                              │
                                                     └─ .dlq (failed messages)      ▼
                                                                          ┌───────────────────────┐
                                                                          │ ReportGenerator       │ pure Java
                                                                          │   score → rating      │
                                                                          │   rating → advice     │
                                                                          ├───────────────────────┤
                                                                          │ ReportStore           │ in memory
                                                                          │   ConcurrentHashMap   │
                                                                          └───────────────────────┘
                                                                                    ▲
                                                               GET /api/reports ────┘
```

This is the **fan-out** completed: one event, two independent consumers, each with its own
queue. One `POST /api/scores` on esg-score-service updates portfolios *and* reports, and
neither consumer knows about the other.

---

## Part 2 — Ratings and recommendations

| Rating | Score range | Seed companies | Recommendations |
|--------|-------------|----------------|-----------------|
| EXCELLENT | 80 – 100 | 1, 2, 6, 11, 16 | maintain practices, third-party assurance, share best practice |
| GOOD | 60 – < 80 | 3, 7, 12, 13, 17 | science-based targets, more renewables, board diversity |
| FAIR | 40 – < 60 | 4, 8, 14, 18, 19 | decarbonisation roadmap, water efficiency, anti-corruption, disclosure |
| POOR | 0 – < 40 | 5, 9, 10, 15, 20 | independent risk assessment, immediate cuts, board oversight, whistleblowing |

`Rating.fromScore` checks from the top down (`>= 80`, `>= 60`, `>= 40`, else POOR), so each
boundary value belongs to the **higher** band — 80.0 is EXCELLENT, 79.99 is GOOD. Tests pin
every boundary.

The texts are fixed constants: the same score always gives the same report, which keeps
experiment runs comparable.

---

## Part 3 — Stateless by design

### What "no database" means here

| | report-service | portfolio-service |
|-|----------------|-------------------|
| Storage | `ConcurrentHashMap` in the JVM heap | PostgreSQL |
| After restart | **empty** | everything still there |
| Dependencies | RabbitMQ only | RabbitMQ, Postgres, esg-score-service |
| Startup work | none | Flyway, Hibernate, connection pool |

There is no JPA, Flyway or JDBC driver in the `pom.xml` at all — not just unused, absent.
That's why the image is smaller and startup and memory are lower (see README measurements).

### Rebuilding after a restart

Reports come back as soon as scores are recalculated (`POST /api/scores` on
esg-score-service). Events published **while report-service is down** wait in its durable
queue and are processed on startup — so nothing is lost during downtime, only what was
already consumed and held in memory.

### Thread safety

Two kinds of threads touch the store at the same time:
- the **RabbitMQ listener thread** writes new reports,
- **HTTP threads** read them for `GET /api/reports`.

A plain `HashMap` can corrupt itself under concurrent writes. `ConcurrentHashMap` is safe, and
`compute()` makes "read the existing report, compare timestamps, maybe replace" one **atomic**
step per company. Without it, two events for the same company processed in parallel could
both see "no newer report" and the older one could win. `ReportStoreTest.concurrentUpdatesKeepTheNewestScore`
fires 1000 updates from 8 threads in random order and checks the newest always wins.

---

## Part 4 — Tests

| Test class | What's real | What's fake |
|------------|-------------|-------------|
| `ReportGeneratorTest` (12) | ratings, boundaries, recommendations, seed spread | nothing |
| `ReportStoreTest` (5) | store incl. 1000 concurrent updates | nothing |
| `ReportServiceTest` (3) | service, generator, store, metrics | clock (fixed) |
| `ReportControllerTest` (4) | controller, `?rating=` parsing, errors | service |
| `ReportServiceIT` (4) | HTTP, RabbitMQ, listener, DLQ | nothing — there is no database |

`ReportServiceIT` uses `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`: a fresh Spring context per
test is the simplest way to start every test with an empty in-memory store.

---

## Part 5 — Hands-on labs

Prerequisite (one terminal per service):

```powershell
docker compose up -d
mvn package -DskipTests
java -jar company-service/target/company-service-0.0.1-SNAPSHOT.jar
java -jar esg-score-service/target/esg-score-service-0.0.1-SNAPSHOT.jar
java -jar portfolio-service/target/portfolio-service-0.0.1-SNAPSHOT.jar
java -jar report-service/target/report-service-0.0.1-SNAPSHOT.jar
```

### Lab 1 — One event, two consumers

```powershell
Invoke-RestMethod http://localhost:8082/api/scores -Method Post | Out-Null
Start-Sleep 2
Invoke-RestMethod http://localhost:8084/api/reports | Format-Table companyId, companyName, totalScore, rating
Invoke-RestMethod http://localhost:8083/api/portfolios | Format-Table id, name, aggregateScore
```

**Look for:** 20 reports and updated portfolios from the same 20 events. In the RabbitMQ UI
(<http://localhost:15672>) → **Exchanges** → `esg.events` → *Bindings*: two queues bound
with `score.updated`.

### Lab 2 — Filter and read a report

```powershell
Invoke-RestMethod "http://localhost:8084/api/reports?rating=POOR" | Format-Table companyId, companyName, totalScore
(Invoke-RestMethod http://localhost:8084/api/reports/20).recommendations
curl.exe -s "http://localhost:8084/api/reports?rating=AMAZING"
```

**Look for:** 5 POOR companies; the last call returns 400 — Spring rejects values that aren't
a `Rating`.

### Lab 3 — Memory is not storage

1. Stop report-service (Ctrl+C) and start it again.
2. `Invoke-RestMethod http://localhost:8084/api/reports` → **empty**.
3. `Invoke-RestMethod http://localhost:8082/api/scores -Method Post` → 20 reports again.

Now stop report-service, recalculate scores, then start it: the 20 events were waiting in
`report-service.score-updated` and the reports are there immediately.

### Lab 4 — The lightest service

With all four services running:

```powershell
foreach ($p in 8081, 8082, 8083, 8084) {
  $m = (Invoke-WebRequest "http://localhost:$p/actuator/prometheus" -UseBasicParsing).Content -split "`n"
  $heap = ($m | Select-String '^jvm_memory_used_bytes.*area="heap"' | ForEach-Object { [double]($_.Line -split ' ')[-1] } | Measure-Object -Sum).Sum
  $classes = ($m | Select-String '^jvm_classes_loaded_classes' | ForEach-Object { ($_.Line -split ' ')[-1] })
  "{0}: heap used {1:N0} MB, classes loaded {2}" -f $p, ($heap / 1MB), $classes
}
```

**Look for:** report-service (8084) loads far fewer classes — no Hibernate, no JDBC. Heap
numbers fluctuate with garbage collection; compare several readings.

---

## Part 6 — Self-check questions

1. Why is a score of exactly 60.0 rated GOOD and not FAIR?
2. What is lost when report-service restarts, and what isn't?
3. Why `ConcurrentHashMap.compute()` instead of `get()` followed by `put()`?
4. Both consumers receive the same event. If report-service is down, does portfolio-service
   still get it?
5. Why does report-service have no `ScoreClient`, while portfolio-service does?
