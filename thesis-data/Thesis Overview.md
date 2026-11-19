# Thesis Overview

**Name**: An Empirical Study of Dockerfile Strategies for Spring Boot Microservices: Build Time, Image Size, Startup Time, and Runtime Performance

**Focus**: How Docker optimization strategies — multi-stage builds, base image selection, and instruction ordering and cache mounts for build caching — affect image size, build time, container startup time, runtime memory, and runtime performance both at idle and under sustained load in a controlled Java/Spring Boot microservice system.

**Abstract**

This thesis presents a controlled empirical study of four Dockerfile variants — a single-stage build, and multi-stage builds with a JDK on Ubuntu, a JRE on Ubuntu, and a JRE on Alpine Linux — applied uniformly to a self-developed system of four Spring Boot microservices. Five metrics are measured: image size, build time, container startup time, runtime memory (at idle and peak under load), and application throughput with p99 latency under sustained load. Additionally, Experiment 2 isolates the effect of build caching on rebuild time in a 2×2 design: instruction ordering (cache-optimal vs. cache-suboptimal) combined with and without a BuildKit cache mount for the Maven repository, measured for a cold build, an incremental rebuild after a source change, and a rebuild on a reused build machine.

## Motivation & Research Framework

Modern software systems increasingly rely on containerized microservice architectures. While Docker has become the de facto standard for packaging and deploying services, the resource footprint of container images — their size, build time, startup latency, and runtime memory — directly impacts infrastructure costs and energy consumption.

Existing studies examine Dockerfile optimization on large heterogeneous OSS repositories or single-service Node.js applications. This thesis focuses on a different context: a controlled, homogeneous Java/Spring Boot microservice system. Java introduces specific characteristics that make this context worth studying separately.

Spring Boot packages everything into a single fat JAR (about 29–62 MB for the four services of this system). The choice of base image therefore has a large proportional effect on final image size — shipping a full JDK and Maven image (about 700 MB uncompressed) to run a 60 MB JAR wastes most of the image. Multi-stage builds and minimal base images are particularly impactful here, and their effect on not just image size but also build time, startup time, runtime memory, and performance under load is not well understood in this specific context.

The JVM also takes 3–8 seconds to start a Spring Boot application, making container startup time a meaningful and measurable metric — differences between image variants are large enough to observe and compare statistically.

Beyond image composition, Docker build caching represents a second, largely independent optimization axis. In real development workflows, source code changes constantly while dependency definitions rarely do. If Dockerfile instructions are ordered incorrectly, every small source change triggers a full Maven dependency re-download — adding several minutes to every rebuild (in a preliminary measurement, resolving the dependencies of one service took about 260 s, compiling and packaging it about 3 s). Correct instruction ordering avoids this cost by keeping the dependency layer cached. BuildKit cache mounts offer a second mechanism: the Maven repository is kept on the build machine outside the image layers, so even a rebuilt layer finds its dependencies locally. This thesis quantifies how large these differences are and whether instruction ordering still matters when a cache mount is used.

## Research Questions

RQ1 - How do multi-stage builds and base image selection affect image size, build time, container startup time, runtime memory, and throughput with p99 latency under load in a homogeneous Java/Spring Boot microservice system?

RQ2 - How do instruction ordering and BuildKit cache mounts in a multi-stage Dockerfile affect Docker build cache reuse and rebuild time, for an incremental rebuild after a source-only change and for a rebuild on a reused build machine?

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
- Measurements: Cold build time, incremental rebuild time and rebuild time on a reused machine (seconds)
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
| S2 — Incremental rebuild | filled, src/ modified, pom.xml unchanged | filled | S2 quantifies how much time correct ordering and the cache mount save during a typical workflow where only source code changes; only `cache-wrong` is expected to re-download |
| S3 — Rebuild on a reused machine | empty (`--no-cache`) | filled | S3 represents a CI runner that builds from scratch but keeps its cache mount; only the mount variants are expected to be fast, regardless of order. The variants without a mount serve as a control. |

5 runs per variant per scenario → Shapiro-Wilk → Kruskal-Wallis across the four variants → pairwise Mann-Whitney U with Bonferroni correction (α = 0.05).

The cache mount is shared by all builds on a machine by default, so it is cleared between variants where a measurement requires it, to prevent one variant from warming it for another. Its benefit only applies to reused build machines such as the self-hosted runner of this study; on ephemeral CI runners the mount is empty on every build. The cache-optimal ordering follows the widespread pattern of resolving dependencies in a separate layer via `mvn dependency:go-offline` [citation: Docker documentation]; cache mounts are described in [citation: Docker BuildKit documentation].

## Reproducibility & CI/CD

Both Measurement Study 1 and Experiment 2 are executed via a GitHub Actions workflow running on a self-hosted runner registered to the thesis repository. The self-hosted runner is a dedicated laptop (Windows 11 Home, Docker Desktop) on which no other workloads run during measurement. All jobs run sequentially on this machine. The host values of the runner are recorded before the final runs.

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
