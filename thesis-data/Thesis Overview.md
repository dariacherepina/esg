# Thesis Overview

**Name**: An Empirical Study of Dockerfile Strategies for Spring Boot Microservices: Build Time, Image Size, Startup Time, and Runtime Performance

**Focus**: How Docker optimization strategies — multi-stage builds, base image selection, and instruction ordering and cache mounts for build caching — affect image size, build time, container startup time, runtime memory, and runtime performance both at idle and under sustained load in a controlled Java/Spring Boot microservice system.

**Abstract**

This thesis presents a controlled empirical study of four Dockerfile variants — a single-stage build, and multi-stage builds with a JDK on Ubuntu, a JRE on Ubuntu, and a JRE on Alpine Linux — applied uniformly to a self-developed system of four Spring Boot microservices. Five metrics are measured: image size, build time, container startup time, runtime memory (at idle and peak under load), and application throughput with p99 latency under sustained load. Additionally, Experiment 2 isolates the effect of build caching on rebuild time in a 2×2 design: instruction ordering (cache-optimal vs. cache-suboptimal) combined with and without a BuildKit cache mount for the Maven repository, measured for a cold build, a rebuild on a reused build machine, and an incremental rebuild after a source change. A supplementary experiment examines how far these caching results generalize to ephemeral GitHub-hosted runners with a remote cache backend.

## Motivation 
Docker has become the standard for packaging and deploying microservices, and with it comes a set of widely repeated recommendations: use multi-stage builds to keep images small, choose Alpine Linux as a minimal base image, and structure Dockerfiles to maximize cache reuse. This thesis documents the effect of these recommendations for a Java/Spring Boot system, measured across the metrics that matter in production: image size, build time, container startup time, runtime memory, and performance under load.


Multi-stage builds and minimal base images are a well-established approach to reducing image size. Fachrudin and Affandi (2025) demonstrate that combining Alpine with multi-stage builds reduces image size by up to 94% compared to a single-stage build, and Rosa et al. (2025) confirm that changing the base image has the most substantial impact on image size among all Dockerfile optimization patterns. For a Spring Boot application the gains are particularly large: the full Maven and JDK build image weighs roughly 700 MB, while the application itself is a fat JAR of 30–60 MB. However, Alpine packages musl libc rather than glibc, and the two differ in their memory allocator. Tjiong (2024) finds that Alpine-based containers consume more energy than expected from their size alone, attributing this to musl’s reduced functionality. This thesis measures how much image size each optimization step removes — stripping Maven, switching from JDK to JRE, switching from Ubuntu to Alpine — and whether the final Alpine image carries a cost in startup time, runtime memory, or throughput under load. 


Development and CI/CD workflows, source code changes constantly while dependency definitions rarely do. If Dockerfile instructions are ordered so that source files and dependency files are copied together, any source change invalidates the dependency download layer. In a preliminary measurement on this system, resolving Maven dependencies took approximately 260 seconds per service; compiling and packaging the source took approximately 3 seconds. Correct instruction ordering avoids this by keeping the dependency layer cached across source changes. BuildKit cache mounts offer a second mechanism, persisting the Maven repository on the build machine across builds regardless of layer invalidation. This thesis measures the actual rebuild cost of wrong instruction ordering, whether a cache mount eliminates that cost, and whether ordering still matters when a mount is in use. 

## Research Questions

RQ1 - How do multi-stage builds and base image selection affect image size, build time, container startup time, runtime memory, and throughput with p99 latency under load in a homogeneous Java/Spring Boot microservice system?

RQ2 - How do instruction ordering and BuildKit cache mounts in a multi-stage Dockerfile affect Docker build cache reuse and rebuild time, for a rebuild on a reused build machine and for an incremental rebuild after a source-only change?

## Research Focus

Four Spring Boot microservices forming an ESG Screening Platform:

| Service | Port | Dependencies |
|---|---|---|
| company-service | 8081 | PostgreSQL, REST server |
| esg-score-service | 8082 | PostgreSQL, RabbitMQ, REST → company-service |
| portfolio-service | 8083 | PostgreSQL, RabbitMQ, REST → esg-score-service |
| report-service | 8084 | RabbitMQ |

## Pre-Step (all experiments)

Before any experiment runs, Hadolint (v2.15.1, default rules) is executed on every Dockerfile variant. Identified Docker smells are fixed and documented. The cleaned Dockerfiles, together with a shared `.dockerignore`, form the consistent baseline for both experiments.

- Hadolint: detects Docker smells per Durieux 2024
- `.dockerignore`: controlled constant — same file across all variants
- Findings documented before experiments begin (`thesis-data/hadolint/`):
  - DL3066 (non-numeric user ID), 12 findings: fixed — all multi-stage and cache variants run as user 10001
  - DL3059 (consecutive `RUN` instructions), 4 findings in `cache-wrong`: intentionally suppressed, because consolidating the instructions would make `cache-wrong` differ from `cache-right` in more than the instruction order
- Limitation: Hadolint reports no finding for the single-stage Dockerfile although it runs as root, because only an explicit `USER root` is flagged

## Experiments

### Measurement Study 1 — Image Variants

**Research Question:** How do multi-stage builds and base image selection affect image size, build time, container startup time, runtime memory, and runtime performance in a Java/Spring Boot microservice system?

The load test phase specifically targets the Alpine variant's behaviour under concurrent load. Alpine uses musl libc instead of glibc, and the two differ in their memory allocator.
glibc uses ptmalloc2, a per-thread arena allocator: each thread gets its own memory arena, reducing lock contention between threads.
musl uses a simpler allocator with a single global lock on the free list — under high thread contention, threads serialise on that lock waiting to allocate or free memory.
The JVM allocates Java objects on its own heap (via thread-local allocation buffers), not through the libc allocator. The allocator therefore affects only the JVM's native memory: JIT compilation, garbage-collector data structures, thread stacks and direct buffers. For a Spring Boot service handling 50 concurrent requests, contention on these native allocations may still show up as reduced throughput or increased p99 latency compared to the glibc-based Ubuntu variant; differences between the Alpine and Ubuntu images as a whole may contribute as well.

Whether this effect is large enough to measure in a Spring Boot workload is not analytically determinable. This motivates the load test phase of Measurement Study 1.

(Paper Title: Mining and Measuring the Impact of Change Patterns for Improving the Size and Build Time of Docker Images (2025); Implementation and Analysis of Container Image Optimization Using Alpine Linux and Multi-Stage Builds (2025))

Independent variable: Dockerfile variant

- Measurement:
  - Image size (MB): compressed (layer sizes in a registry, i.e. what a pull transfers) and uncompressed
  - Build time (seconds), cold build (`--no-cache`)
  - Container startup time (seconds): `docker run` until `/actuator/health` is UP, and the JVM-reported application ready time
  - Runtime memory at idle (MiB, 30 s after startup) and peak memory under load (MiB)
  - Throughput (req/s) and p99 latency (ms) under load
- Load test: k6 against esg-score-service only (`GET /api/scores/{id}`, random id 1–20), 50 virtual users for 60 s after a 10 s warm-up; only the measure phase is evaluated
- Pipeline: GitHub Actions
- All repeated metrics (5 runs per variant → Shapiro-Wilk normality test → Kruskal-Wallis (non-parametric, α = 0.05) → pairwise Mann-Whitney U with Bonferroni correction as post-hoc test). Image size is deterministic (one value per build) and reported as % reduction per step.
- All variants run with identical JVM flags (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75`) and a 512 MB memory limit; runs are interleaved across variants.
- All variants use the same Maven build steps (`dependency:go-offline`, then `package -DskipTests`), so they differ only in the number of stages and the runtime image.

| Variant | Base image |
|---|---|
| Single-stage (baseline) | maven:3.9.16-eclipse-temurin-21 — JDK + Maven, full image |
| Multi-stage + JDK | eclipse-temurin:21.0.13_9-jdk - Ubuntu, JDK only (no Maven) |
| Multi-stage + JRE | eclipse-temurin:21.0.13_9-jre — Ubuntu, JRE only |
| Multi-stage + Alpine | eclipse-temurin:21.0.13_9-jre-alpine — Alpine, JRE only |

 The multi-stage variants build with `maven:3.9.16-eclipse-temurin-21` in their first stage. The size comparison isolates three steps: Maven and sources removed (single → multi-JDK), JDK → JRE (multi-JDK → multi-JRE), and Ubuntu → Alpine (multi-JRE → multi-Alpine).

Known limitations:
- The single-stage variant runs on the JDK of the Maven image (Temurin 21.0.12.1, Ubuntu 24.04), the multi-stage variants on Temurin 21.0.13 (Ubuntu 26.04 or Alpine 3.24). This is inherent to the official images.
- Cold build time is dominated by resolving dependencies from Maven Central (about 99 % in a preliminary measurement) and therefore sensitive to network conditions. `dependency:go-offline` also resolves test-scoped dependencies and unused plugin dependencies, which increases the absolute build time of all variants equally.

### Experiment 2 — Build Time & Cache Efficiency

- Question: How do instruction order and BuildKit cache mounts affect Docker build caching and rebuild time?
- Independent variables: Layer order in Dockerfile (right / wrong) × BuildKit cache mount for `/root/.m2` (with / without)
- Measurements: Cold build time, rebuild time on a reused machine and incremental rebuild time (seconds)
- Pipeline: GitHub Actions

| Variant | Order | Cache mount | Description |
|---|---|---|---|
| `Dockerfile.cache-wrong` | wrong | no | COPY pom.xml + src/ together → dependency:go-offline → package; any src/ change invalidates the dependency download layer |
| `Dockerfile.cache-right` | right | no | COPY pom.xml → dependency:go-offline → COPY src/ → package; dependency layer survives a src/-only change |
| `Dockerfile.cache-wrong-mount` | wrong | yes | as `cache-wrong`, with `RUN --mount=type=cache,target=/root/.m2` on both Maven steps |
| `Dockerfile.cache-right-mount` | right | yes | as `cache-right`, with `RUN --mount=type=cache,target=/root/.m2` on both Maven steps |

All four variants use the same Maven commands and the same runtime stage; each pair differs in exactly one factor. This allows three comparisons: the effect of instruction order, the effect of the cache mount, and whether instruction order still matters when a cache mount is used.

| Scenario | Layer cache | Cache mount | Expected result |
|---|---|---|---|
| S1 — Cold build | empty (`--no-cache`) | cleared before every run | S1 establishes the fair baseline: no cache advantage for any variant |
| S2 — Rebuild on a reused machine | deleted (`docker builder prune --filter type=regular`) | filled by S1, kept | S2 represents a CI runner that builds from scratch but keeps its cache mount; only the mount variants are expected to be fast, regardless of order. The variants without a mount serve as a control. |
| S3 — Incremental rebuild | filled, src/ modified, pom.xml unchanged | filled | S3 quantifies how much time correct ordering and the cache mount save during a typical workflow where only source code changes; only `cache-wrong` is expected to re-download |

The scenarios increase step by step in what is cached: nothing (S1), only the cache mount (S2), the layer cache and the cache mount (S3).

| Scenario | Layer cache | Cache mount | Situation | Expected |
|---|---|---|---|---|
| S1 cold | ❌ | ❌ | brand-new machine | all 4 slow |
| S2 reused machine | ❌ | ✅ | same machine, layers gone | only the `-mount` variants fast |
| S3 incremental | ✅ | ✅ | only src/ changed | only `cache-wrong` slow |

`--no-cache` also ignores BuildKit cache mounts (verified in a preliminary test: 144 s with `--no-cache` and a filled mount vs. 20 s after deleting only the layer cache). S1 therefore uses `--no-cache` plus an explicitly cleared mount, while S2 deletes only the layer cache and keeps the mount.

5 runs per variant per scenario → Shapiro-Wilk → Kruskal-Wallis across the four variants → pairwise Mann-Whitney U with Bonferroni correction (α = 0.05).

Services: Experiment 2 examines a caching mechanism that is independent of the application code; its effect size depends on the volume of resolved dependencies. It is therefore measured on the services with the smallest (report-service: no database stack) and the largest dependency set (esg-score-service: database stack and RabbitMQ, about 252 MB of resolved artifacts). The other two services share almost the same dependency set as esg-score-service.

The cache mount is shared by all builds on a machine by default, so it is cleared between variants where a measurement requires it, to prevent one variant from warming it for another. Its benefit only applies to reused build machines such as the self-hosted runner of this study; on ephemeral CI runners the mount is empty on every build. The cache-optimal ordering follows the widespread pattern of resolving dependencies in a separate layer via `mvn dependency:go-offline` [citation: Docker documentation]; cache mounts are described in [citation: Docker BuildKit documentation].

### Supplementary Experiment 3 — Cache Backends on Hosted Runners (Generalizability)

Experiment 2 runs on a persistent self-hosted runner, where the layer cache survives between builds. Without a further test, a reader could assume that its results transfer to any CI setup. This supplementary experiment bounds that claim: it examines whether a correctly ordered Dockerfile still benefits from caching on ephemeral GitHub-hosted runners, where every job starts on a fresh machine and the cache has to be exported to and imported from a remote backend. It is reported under threats to validity / generalizability, not as a separate research question, and is carried out after the main measurements.

- Question: How much of the caching benefit of a correctly ordered multi-stage Dockerfile is retained on ephemeral hosted runners with a remote cache backend (GitHub Actions cache), compared with a persistent local cache?
- Independent variable: Cache configuration (runner type and cache backend)
- Dockerfile: `Dockerfile.cache-right` of one service only (constant across all configurations)
- Measurements: cold build time and warm build time after a src/-only change (seconds), size of the exported cache entry (MB)

| Configuration | Runner | Cache flags | Hypothesis (cold) | Hypothesis (warm, src/ change) |
|---|---|---|---|---|
| A — no cache | GitHub-hosted | none | baseline | same as cold |
| B — gha `mode=min` | GitHub-hosted | `--cache-to=type=gha,mode=min --cache-from=type=gha` | slower than A (export overhead) | no improvement: `mode=min` exports only the layers of the final image, so the dependency layer of the build stage is not cached |
| C — gha `mode=max` | GitHub-hosted | `--cache-to=type=gha,mode=max --cache-from=type=gha` | slower than A (export overhead) | faster than cold: all stages are exported, so the dependency layer is restored from the remote cache |
| D — local persistent | self-hosted laptop | none (local layer cache) | – | fast; taken from Experiment 2 (`cache-right`, S1 as cold, S3 as warm) |

Hypotheses:
- H1: Correct instruction ordering is necessary but not sufficient on ephemeral runners: the same `cache-right` Dockerfile yields no speed-up without a remote cache (A) and with `mode=min` (B).
- H2: `mode=min` gives no benefit for multi-stage builds although the workflow runs without errors and a cache entry is created. Docker's documentation states that `mode=min` exports only the layers of the resulting image; the experiment shows the practical effect with measured numbers.
- H3: A persistent local cache (D) gives a larger relative speed-up than a remote cache backend (C).

Design decisions:
- **Comparison by relative speed-up, not absolute time.** D runs on the laptop, A–C on GitHub-hosted virtual machines, which differ in CPU, memory and network (the Maven download is expected to be much faster in a data centre). Absolute build times are therefore not comparable across runner types; each configuration is evaluated by its warm/cold ratio on its own machine.
- **Cold and warm as two jobs in one workflow run.** Job 1 builds cold and exports the cache; job 2 starts on a new machine, applies the src/ change inside the job (appending a unique comment to a `.java` file, as in Experiment 2) and builds warm. No experiment commits are pushed to the repository.
- **A unique cache scope per workflow run** (`scope=run-${{ github.run_id }}`), so every cold build starts with an empty remote cache and runs cannot influence each other; cache entries are not deleted by hand.
- **5 runs per configuration and scenario**, as in the rest of the study (with 3 runs, a Mann-Whitney U test cannot reach p < 0.05).
- Build time includes cache import and export, because that is the cost a developer experiences.
- The cache entry size is measured, not predicted; it shows whether `mode=min` exported only the final stage.

Analysis: per configuration, cold vs. warm with Mann-Whitney U and the median speed-up (cold/warm); across configurations, the speed-ups are compared descriptively. Results are phrased as "shows" for this system and setup, not as general proof.

Limitations: one service and one Dockerfile; GitHub-hosted runner hardware is not controlled and may vary between jobs; the GitHub Actions cache service has its own size limits and network performance; BuildKit cache mounts (Experiment 2, S2) are not restored on hosted runners and are not part of this experiment.

## Reproducibility & CI/CD

Both Measurement Study 1 and Experiment 2 are executed via a GitHub Actions workflow running on a self-hosted runner registered to the thesis repository. The self-hosted runner is the author's laptop (Windows 11 Home, Docker Desktop), used exclusively for the measurements while they run: other containers are stopped (the workflow refuses to start otherwise) and no other applications run. All jobs run sequentially on this machine (`.github/workflows/experiments.yml`, started by hand). Measurement Study 1 and Experiment 2 run as separate workflow runs. Each run records the host environment (Windows, CPU, RAM, Docker, tool versions) in `environment.txt` next to the measurements. The build cache is pruned automatically at points where it is not reused, because the laptop has limited disk space. Only the supplementary Experiment 3 (configurations A–C) runs on GitHub-hosted runners (`ubuntu-latest`), in a separate workflow.

**Infrastructure:**

- Docker Compose: postgres:16.15-alpine + rabbitmq:4.1.8-management-alpine started before measurement phases; company-service is additionally started for the load test (used once per run to seed the scores)
- Spring Boot Actuator: /actuator/health as startup probe; /actuator/prometheus used for the JVM-reported application ready time
- Runtime memory measured via docker stats --no-stream; no Prometheus or Grafana required
- Load test executed via k6 v2.4.0 (single binary, results exported as JSON parsed by the measurement script)
- Image size measured via a local registry (registry:2.8.3)
- Frontend (Nginx) is an UI profile; not part of any experiment

Infrastructure:

| Tag | Pinned? |
|---|---|
| postgres:16.15-alpine | ✅ exact version |
| rabbitmq:4.1.8-management-alpine | ✅ exact version |
| prom/prometheus:v3.16.0 | ✅ exact version (not used for measurements) |
| grafana/grafana:13.3.0 | ✅ exact version (not used for measurements) |
| registry:2.8.3 | ✅ exact version (local registry for the size measurement) |
| nginx:1.27-alpine | ❌ not pinned (frontend, not part of any experiment) |

## Key literature:

1. Cito, J., Schermann, G., Wittern, J. E., Leitner, P., Zumberi, S., & Gall, H. C. (2017). An Empirical Analysis of the Docker Container Ecosystem on GitHub. In Proceedings of the 2017 IEEE/ACM 14th International Conference on Mining Software Repositories (MSR '17), pp. 323–333. IEEE.
2. Rosa, G., Scalabrino, S., & Oliveto, R. (2022). Assessing and Improving the Quality of Docker Artifacts. IEEE/ACM International Conference on Software Engineering (ICSE Doctoral Symposium / Summary).
3. Rastogi, V., Davidson, D., De Carli, L., Jha, S., & McDaniel, P. (2017). Cimplifier: Automatically Debloating Containers. In Proceedings of the 2017 11th Joint Meeting of the European Software Engineering Conference and the ACM SIGSOFT Symposium on the Foundations of Software Engineering (ESEC/FSE '17), pp. 476–486. ACM.
4. Zhu, Z., Chen, T., Liu, C., Liu, H., Song, Q., Xu, Z., & Liu, Y. (2025). Doctor: Optimizing Container Rebuild Efficiency by Instruction Re-Orchestration. In Proceedings of the 2025 ACM SIGSOFT International Symposium on Software Testing and Analysis (ISSTA '25), pp. 1–24. ACM.
5. Durieux, T. (2024). Empirical Study of the Docker Smells Impact on the Image Size. In Proceedings of the 2024 IEEE/ACM 46th International Conference on Software Engineering (ICSE '24), pp. 1–12. IEEE/ACM.
6. Badisa, N., Grandhi, J. K., Kallam, L., Eda, M. R., Srinivasu, N., & Bulla, S. (2023). Efficient Docker Image Optimization using Multi-Stage Builds and Nginx for Enhanced Application Deployment. Research Square Preprint, DOI: 10.21203/rs.3.rs-3276965/v1.
7. Rosa, G., Zappone, F., Scalabrino, S., & Oliveto, R. (2024). Fixing Dockerfile Smells: An Empirical Study. Empirical Software Engineering, Vol. 29, Art. 108, Springer.
8. Fachrudin, M. R., & Affandi, A. S. (2025). Implementation and Analysis of Container Image Optimization Using Alpine Linux and Multi-Stage Builds. TEKNIKA Journal, Vol. 14, No. 1, pp. 9–18. DOI: 10.34148/teknika.v14i1.1118.
9. Henkel, J., Bird, C., Lahiri, S. K., & Reps, T. (2020). Learning from, Understanding, and Supporting DevOps Artifacts for Docker. In Proceedings of the 2020 IEEE/ACM 42nd International Conference on Software Engineering (ICSE '20), pp. 38–49. ACM.
10. Rastogi, V., Niddodi, C., Mohan, S., & Jha, S. (2017). New Directions for Container Debloating. In Proceedings of the 2017 Workshop on Forming an Ecosystem Around Software Transformation (FEAST '17 / ESEC/FSE), pp. 51–56. ACM.
11. Rosa, G., Guglielmi, E., Iannone, M., Scalabrino, S., & Oliveto, R. (2025). Mining and Measuring the Impact of Change Patterns for Improving the Size and Build Time of Docker Images. Empirical Software Engineering, Vol. 30, Art. 150, Springer. DOI: 10.1007/s10664-025-10680-8.
12. Wu, Y., Zhang, Y., Wang, T., & Wang, H. (2022). Understanding and Predicting Docker Build Duration. In Proceedings of the 37th IEEE/ACM International Conference on Automated Software Engineering (ASE '22), pp. 1–12. ACM.
