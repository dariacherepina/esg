# Thesis Overview

**Name**: An Empirical Study of Dockerfile Strategies for Spring Boot Microservices Across the Container Lifecycle: Build Time, Image Size, Startup Time, and Runtime Performance

**Focus**: How the choice of Dockerfile strategy and runtime base image — single-stage vs. multi-stage, JDK vs. JRE, Ubuntu (glibc) vs. Alpine (musl) — trades off across the container lifecycle (build, transfer, startup and runtime under load) in a controlled Java/Spring Boot microservice system, where the differences between the Ubuntu and the Alpine runtime image come from, and under which conditions the Alpine-based image is the preferable runtime image for Java microservices.

**Abstract**

This thesis presents a controlled empirical study of four Dockerfile variants — a single-stage build, and multi-stage builds with a JDK on Ubuntu, a JRE on Ubuntu, and a JRE on Alpine Linux — applied uniformly to a self-developed system of four Spring Boot microservices. Image size, cold build time, container startup time and idle memory are measured for all variants and services. The runtime comparison concentrates on the two JRE images, which differ only in the distribution and its C library (glibc vs. musl): a k6 load test runs at six concurrency levels from 10 to 400 virtual users under a fixed CPU and memory limit, with ten repetitions in randomized order, and records throughput, p99 latency, CPU time per request, peak memory (container and kernel view), JVM heap, thread and garbage-collection metrics. A control variant — the Ubuntu JRE image with glibc limited to two malloc arenas (`MALLOC_ARENA_MAX=2`) — tests whether a memory difference between the images is caused by glibc's allocator, and the JVM's Native Memory Tracking separates memory the JVM accounts for from memory outside it. Long runs of 30 minutes show whether memory keeps growing over time. A lifecycle analysis then uses these measurements to answer under which deployment scenarios the Alpine JRE image is the preferable runtime image: the measured build, transfer, startup and runtime costs are aggregated per image to determine break-even points and the Pareto-optimal (non-dominated) images.

## Motivation 
Docker has become the standard for packaging and deploying microservices, and with it comes a set of widely repeated recommendations: use multi-stage builds to keep images small and choose Alpine Linux as a minimal base image. This thesis documents the effect of these recommendations for a Java/Spring Boot system, measured across the metrics that matter in production: image size, build time, container startup time, runtime memory, and performance under load.


Multi-stage builds and minimal base images are a well-established approach to reducing image size. Fachrudin and Affandi (2025) demonstrate that combining Alpine with multi-stage builds reduces image size by up to 94% compared to a single-stage build, and Rosa et al. (2025) confirm that changing the base image has the most substantial impact on image size among all Dockerfile optimization patterns. However, Alpine packages musl libc rather than glibc, and the two differ in their memory allocator. Tjiong (2023) finds that Alpine-based containers consume more energy than expected from their size alone, attributing this to musl's reduced functionality. This thesis measures how much image size each optimization step removes — stripping Maven, switching from JDK to JRE, switching from Ubuntu to Alpine — and whether the final Alpine image carries a cost in startup time, runtime memory, CPU or throughput under load.


Existing studies of image optimization mostly report image size and build time; where runtime is measured, it is usually a single load level without an explanation of the observed differences. This thesis therefore examines the runtime behaviour of the Ubuntu and the Alpine JRE image in depth: across a range of concurrency levels up to overload, under a realistic CPU limit, over long runs, and with control conditions that test where a difference comes from.

## Research Questions

RQ1 - How do the four image variants (single-stage, multi-stage JDK, multi-stage JRE on Ubuntu, multi-stage JRE on Alpine) compare in build time, image size, startup time and memory, and how do the Ubuntu and the Alpine JRE image compare in throughput, p99 latency, CPU time per request and memory as concurrency increases from 10 to 400 users?

RQ2 - Under which deployment scenarios is the Alpine JRE image the preferable runtime image for Spring Boot microservices, considering the trade-offs measured in RQ1?

## Research Focus

Four Spring Boot microservices forming an ESG Screening Platform:

| Service | Port | Dependencies |
|---|---|---|
| company-service | 8081 | PostgreSQL, REST server |
| esg-score-service | 8082 | PostgreSQL, RabbitMQ, REST → company-service |
| portfolio-service | 8083 | PostgreSQL, RabbitMQ, REST → esg-score-service |
| report-service | 8084 | RabbitMQ |

Out of scope: build caching (instruction order, BuildKit cache mounts, remote cache backends). It acts on the build stage only and works the same for every runtime image, so it does not change the comparison between the images; build time is measured as a cold build.

## Pre-Step

Before any measurement runs, Hadolint (v2.15.1, default rules) is executed on every Dockerfile variant. Identified Docker smells are fixed and documented. The cleaned Dockerfiles, together with a shared `.dockerignore`, form the consistent baseline for the study.

- Hadolint: detects Docker smells per Durieux 2024
- `.dockerignore`: controlled constant — same file across all variants
- Findings documented before the measurements begin (`thesis-data/hadolint/`):
  - DL3066 (non-numeric user ID): fixed — all multi-stage variants run as user 10001
- Limitation: Hadolint reports no finding for the single-stage Dockerfile although it runs as root, because only an explicit `USER root` is flagged
- The reports in `thesis-data/hadolint/` were created when the repository still contained the build-caching Dockerfiles; they are regenerated before the final measurements.

## Measurement Study 1 — Image Variants

**Research Question (RQ1):** How do the four image variants compare in build time, image size, startup time and memory, and how do the Ubuntu and the Alpine JRE image compare under increasing load?

Measurement Study 1 compares the image variants on observable metrics — what a team gains and loses by choosing one image over another — and, for the runtime comparison of the two JRE images, tests explanations for the differences it observes. The image part (A) covers all variants and services; the runtime parts (B–E) concentrate on the two JRE images of one service.

(Paper Title: Mining and Measuring the Impact of Change Patterns for Improving the Size and Build Time of Docker Images (2025); Implementation and Analysis of Container Image Optimization Using Alpine Linux and Multi-Stage Builds (2025))

| Variant | Base image |
|---|---|
| Single-stage (baseline) | eclipse-temurin:21.0.13_9-jdk + Maven 3.9.16 — Ubuntu, JDK + Maven, full image |
| Multi-stage + JDK | eclipse-temurin:21.0.13_9-jdk — Ubuntu, JDK only (no Maven) |
| Multi-stage + JRE | eclipse-temurin:21.0.13_9-jre — Ubuntu, JRE only |
| Multi-stage + Alpine | eclipse-temurin:21.0.13_9-jre-alpine — Alpine, JRE only |

All variants run with identical JVM flags (`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75`), a 512 MB memory limit and a limit of 4 CPUs. The heap is set to 75% of the memory (384 MB) because Java's default of 25% would be too small for Spring Boot under load, which means the absolute memory values apply only to this setting while the comparison between variants stays fair. With less than 2 GB of memory the JVM selects the Serial garbage collector by itself; this is recorded in the GC log of every run.

**Ergonomics check (done on 2026-11-23):** the JVM chooses the GC, heap size and thread counts automatically from the CPUs and memory it detects. If the images differed there, a GC difference between them would have a trivial cause. Both JRE images were started with the measurement's limits and flags and compared with `java -version`, `-Xlog:gc*` and `-XX:+PrintFlagsFinal`:

| Setting | Ubuntu JRE | Alpine JRE |
|---|---|---|
| JVM build | Temurin-21.0.13+9 (21.0.13+9-LTS) | Temurin-21.0.13+9 (21.0.13+9-LTS) |
| GC | Serial (ergonomic) | Serial (ergonomic) |
| Maximum / initial heap | 384 MB / 8 MB | 384 MB / 8 MB |
| CPUs detected (2-CPU / 4-CPU limit) | 2 / 4 of 16 | 2 / 4 of 16 |
| JIT compiler threads (2 / 4 CPUs / no limit) | 2 / 3 / 12 | 2 / 3 / 12 |
| GC threads | 1 (Serial) | 1 (Serial) |
| Compressed oops, thread stack size | enabled, 1 MB | enabled, 1 MB |

The settings are identical, so differences between the images cannot come from JVM ergonomics. With 4 CPUs the JVM still selects Serial and the same heap on both images. Side observation: the number of JIT compiler threads follows the CPU limit (2, 3 or 12), so warm-up takes longer under a limit; this is why the warm-up is 30 s and measured separately.

### Part A — Image metrics (all variants, all four services)

- Image size (MB): compressed (layer sizes in a registry, i.e. what a pull transfers) and uncompressed; reported as % reduction per step
- Build time (seconds), cold build (`--no-cache`); base images are pulled once beforehand and not timed
- Container startup time (seconds): `docker run` until `/actuator/health` is UP, and the JVM-reported application ready time
- Memory at idle, 30 s after startup: container memory (`docker stats`) and resident memory of the Java process (kernel RSS, `/proc/1/status`)
- 5 runs per variant and service, interleaved (run 1 of every image, then run 2, …)

### Part B — Runtime under load (Ubuntu JRE vs. Alpine JRE, esg-score-service)

The runtime part compares the two images that differ only in the distribution and its C library. The single-stage and JDK variants are left out: they contain the same JVM on the same distribution as the Ubuntu JRE image, so their runtime behaviour adds little, while every variant multiplies the measurement time.

| Variant | Image | Purpose |
|---|---|---|
| `multi-jre` | Ubuntu JRE (glibc 2.43) | main comparison |
| `multi` | Alpine JRE (musl 1.2.6) | main comparison |
| `multi-jre-arena2` | Ubuntu JRE, started with `MALLOC_ARENA_MAX=2` | control: tests whether a memory difference comes from glibc's malloc arenas |

**Design**
- k6 against esg-score-service (`GET /api/scores/{id}`, random id 1–20), a closed model with a constant number of virtual users
- Concurrency series: 10 / 25 / 50 / 100 / 200 / 400 virtual users. 400 lies above Tomcat's 200 worker threads and is an overload point where requests queue.
- Each run: a fresh container, a 30 s warm-up at 10 users as a separate k6 run (not evaluated), then 60 s measurement. CPU time and GC counters are read between the two, so JIT compilation during the warm-up does not count towards the measured values.
- 10 repetitions; within each repetition all level × variant combinations run once in a random order (fixed seed, reproducible), so that drift of the machine during the session does not hit one variant or level systematically
- 6 levels × 3 variants × 10 repetitions = 180 runs, about 6 hours

**Measurements per run**

| Metric | Source | Why |
|---|---|---|
| Throughput (req/s), p99 and median latency (ms), failed requests | k6 summary of the measure phase | the performance a user experiences |
| CPU time per request (µs), average cores used | user + system CPU time of the Java process (`/proc/1/stat`) before and after the measured run | equal throughput does not mean equal CPU cost; CPU per request enters the cost model of RQ2 directly |
| Peak memory: container (`docker stats`, every 2 s) and kernel peak RSS (`VmHWM`) | Docker, `/proc/1/status` | the memory a node has to reserve; VmHWM catches peaks the 2 s sampling misses |
| Memory time series (every 2 s) | Docker, `/proc/1/status` | when a memory difference forms (startup, warm-up, under load) |
| Heap used and committed, non-heap used, peak threads | Micrometer via `/actuator/prometheus` | whether a memory difference lies in the Java heap or outside it |
| GC pauses: count, total time, p50, p95, maximum | Micrometer and the JVM's GC log (`-Xlog:gc`) | whether GC behaves differently, and whether a difference is systematic or a few outliers |
| GC normalized: pauses per 1,000 requests, share of time paused (%), allocation rate (MiB/s) and allocation per request (KiB) | Micrometer (`jvm_gc_memory_allocated_bytes_total`) and the k6 request count | a variant that serves fewer requests allocates less and collects less often; per unit of work, a GC difference can be told apart from a throughput difference |
| Time-to-safepoint: count, p50, p95, maximum (µs) | the JVM's safepoint log (`-Xlog:safepoint`) | how long it takes until all application threads have stopped for a pause; thread scheduling and locking are implemented differently in glibc and musl, so this is where an OS-level difference could lengthen pauses although the GC itself is equally fast |

**How the normalized GC metrics are read:**

| Observation after normalizing | Interpretation |
|---|---|
| the GC difference disappears | GC behaviour is a consequence of different throughput; the cause lies elsewhere (allocator, JVM build) |
| GCs or allocation per request still differ | the Java code or a library allocates differently on the two images; unusual, investigated separately |
| pause length or time-to-safepoint still differs | something affects how fast the pause itself completes; Part E tests whether this depends on the collector |

**Why a CPU limit of 4:** production containers rarely see all CPUs of a host, and the limit keeps the service from competing with the load generator (k6 runs on the same machine) for CPU. The value was chosen in a check on 2026-11-23 (Ubuntu JRE, 10 / 50 / 200 users, one run each):

| Limit | Throughput at 10 / 50 / 200 users (req/s) | CPU per request (µs) | Cores used |
|---|---|---|---|
| 2 CPUs | 629 / 540 / 577 | 3187 / 3721 / 3490 | 1.99 at every level |
| 4 CPUs | 3266 / 3299 / 3144 | 836 / 1179 / 1266 | 2.7 / 3.9 / 3.9 |
| none | 3571 / 6373 / 6841 | 700 / 647 / 657 | 2.5 / 4.1 / 4.4 |

With 2 CPUs the service was CPU-bound already at 10 users and needed 4–5 times the CPU per request: the JVM's 2 JIT compiler threads compete with the request threads for the same 2 CPUs, so the code stays unoptimized and every request stays expensive. Time-to-safepoint rose to 76 ms at 200 users, because threads waiting for a CPU react late to the stop request. Such a setup would compare a CPU-starved JVM rather than the images. With 4 CPUs, CPU cost at low load is close to the unlimited case, and the CPU limit is reached at about 50 users, so the series covers both the range below the limit (10, 25 users) and the range at the limit (50–400 users), where differences in CPU efficiency between the images show in latency and CPU per request. Without a limit, the service did not use more than about 4.4 cores; there the bottleneck is no longer the CPU (likely the database pool of 10 connections), and k6 shares the host's CPUs without any separation.

**Why the arena control:** glibc's allocator creates up to 8 × CPU count separate memory pools (arenas) so that threads rarely wait for each other; each pool keeps freed memory for reuse, which counts as resident memory. musl's allocator (mallocng) has no per-thread arenas. `MALLOC_ARENA_MAX=2` limits glibc to two arenas without changing the image. If the memory difference between the Ubuntu and the Alpine image shrinks in the control variant, the arenas explain that share of it. The control also shows the price of the setting: fewer arenas can mean more waiting under high concurrency.

### Part C — Long runs (memory over time)

- 200 virtual users for 30 minutes, 3 runs per variant (about 5 hours), same measurements as Part B
- Additional metric: RSS trend after the first 5 minutes of load (MiB per 10 minutes, least-squares slope). A positive trend means memory keeps growing (e.g. allocator fragmentation); a trend near zero means it levels off.

### Part D — Explanation runs (Native Memory Tracking)

- Separate runs with `-XX:NativeMemoryTracking=summary`; the JVM prints its memory per category (heap, metaspace, code cache, compiler, threads, …) at exit
- Unaccounted memory = kernel RSS − NMT total: memory the process holds that the JVM does not account for (libc allocator overhead and retained free memory, shared libraries)
- If the Ubuntu image holds more memory than the Alpine image while the NMT totals are equal, the difference lies outside the JVM, i.e. in the C library or other system libraries
- Not part of the main runs, because NMT itself costs memory (about 5 MiB) and some CPU; 5 runs at 50 and 200 users

### Part E — GC experiment (variant × garbage collector)

If a GC difference between the images remains after normalizing (Part B), this part tests whether it depends on the collector. Every variant runs with every collector; the heap limit (384 MB), CPU and memory limits and the load stay the same.

| | Serial | G1 |
|---|---|---|
| Ubuntu JRE | | |
| Alpine JRE | | |
| Ubuntu JRE + `MALLOC_ARENA_MAX=2` | | |

- Serial: one GC thread, every pause stops the application completely — the simplest case with the fewest side effects, and what the JVM chooses by itself for this container size
- G1: the JVM's default on larger machines and the realistic production choice; it runs concurrent GC threads next to the application, which compete for the 4 CPUs
- Parallel and ZGC are supported by the script (`-LoadGcs parallel,zgc`) but not part of the planned runs; ZGC on JDK 21 is only generational with `-XX:+ZGenerational`
- 50 and 200 users (where the GC difference was largest in the preliminary runs), 10 runs each: 2 levels × 3 variants × 2 GCs × 10 = 120 runs, about 4 hours

| Result | Interpretation |
|---|---|
| the difference appears with every GC | the cause lies outside the GC: allocator, JVM build, thread scheduling |
| the difference appears only with G1 | an interaction: concurrent GC threads compete with the application for the 4 CPUs, so a difference in CPU efficiency shows here first |
| Ubuntu vs. Alpine differs, Ubuntu + arena2 vs. Alpine does not | points to glibc's allocator |

**Detail runs (`-GcDetail`, 3 runs at 200 users, Serial and G1):** a GC phase log (`-Xlog:gc+phases=debug`) splits each pause into its phases, showing whether one phase differs; a Java Flight Recording (written at JVM exit) records GC, allocation, thread and CPU events for inspection in JDK Mission Control. Both cost some overhead and are not part of the measured runs.

Observation from the trial runs: on the Alpine JRE, Java Flight Recorder's default mode cannot write its recording — the dump at JVM exit fails with `Unable to complete I/O operation when dumping recording` and leaves a 0-byte file, reproducible with a bare `java -XX:StartFlightRecording=dumponexit=true,filename=… -version`, while the same command works on the Ubuntu JRE. Keeping the recording in memory (`disk=false`, 32 MB buffer) works on both; the detail runs use this mode for both images. This is a functional difference of the musl build that matters for operations (diagnostics), independent of performance.

ZGC notes (if used): its pauses are logged in a different format, so the pause percentiles from the GC log are not available, and its allocation counter is not directly comparable with Serial and G1.

**Possible causes of a GC difference** (hypotheses, to be confirmed by the measurements above): different JVM ergonomics or build (ruled out by the ergonomics check); different throughput and therefore allocation rate (checked by the normalized metrics); time-to-safepoint, because glibc and musl implement threads and locking differently (checked by the safepoint log); native GC data structures allocated through libc `malloc` (not measured directly; the arena control gives an indication).

### Statistics

- Every repeated metric, per service (Part A) or per concurrency level (Parts B–D): Shapiro-Wilk per variant → Kruskal-Wallis across the variants (non-parametric, α = 0.05) → pairwise Mann-Whitney U with Bonferroni correction as post-hoc test
- Effect size: Cliff's δ for every pair (|δ| < 0.147 negligible, < 0.33 small, < 0.474 medium, otherwise large)
- With 10 runs per group the smallest possible two-sided Mann-Whitney p is about 1e-5, so the 3 post-hoc pairs per level can reach significance even after correction; with 5 runs (Part A, 4 variants, 6 pairs) the smallest Bonferroni-adjusted p is 0.048
- The concurrency series is additionally reported as curves (median per level and variant), to show at which level the images start to differ
- Image size is deterministic (one value per build)

### Possible explanations

If the Alpine image differs from the Ubuntu JRE image, several properties of the images could contribute:
- Alpine packages musl libc rather than glibc. glibc's allocator (ptmalloc2) uses multiple arenas so that concurrent threads can allocate in parallel, at the cost of higher memory usage; musl's allocator (mallocng since version 1.2.1; the Alpine image of this study ships musl 1.2.6) keeps shared metadata for all threads instead of per-thread arenas. The arena control (Part B) and Native Memory Tracking (Part D) test this explanation.
- For Redis, Barba Roque et al. (2025) attribute the higher energy consumption on Alpine not to the allocator — replacing musl's allocator with jemalloc left most of the difference in place — but to musl's `memcpy` implementation.
- The JVM allocates Java objects on its own heap (via thread-local allocation buffers) and copies Java arrays with JIT-generated code, not through libc. libc is used only in the JVM's native work: JIT compilation, garbage-collector data structures, thread creation and stacks, class loading, name resolution and direct buffers. The only Java workload in Tjiong (2023), a Minecraft server, consumed more energy on Alpine without being slower, mainly during start-up.
- On Alpine, the JVM itself is a different build (compiled against musl with a different toolchain), and the distributions differ in further system libraries.

### Known limitations

- Cold build time is dominated by resolving dependencies from Maven Central (about 99 % in a preliminary measurement) and therefore sensitive to network conditions. `dependency:go-offline` also resolves test-scoped dependencies and unused plugin dependencies, which increases the absolute build time of all variants equally.
- No Alpine-with-glibc control: unlike Barba Roque et al. (2025), this study has no Alpine image with glibc, because no official Temurin JRE build for that combination exists. The arena control isolates one property of glibc (the number of arenas), not the C library as a whole; remaining differences describe the images as delivered.
- One service and one read endpoint for the runtime part; the request path is dominated by a database lookup (HikariCP pool of 10), so CPU-bound workloads could show larger differences.
- Spring Boot and JVM defaults (200 Tomcat threads, Serial GC selected for 512 MB) are kept; other configurations — G1, other memory or CPU limits — could change both the absolute values and the difference between the images.
- k6 runs on the same machine as the service; the CPU limit of the container reduces, but does not remove, competition for resources.
- The CPU time per request includes the JVM's background work during the measurement (remaining JIT compilation, GC) — which is intended, since it is part of the cost of serving requests.
- The laptop must run on mains power: on battery, Windows throttles the CPU (observed in a trial run: throughput dropped by orders of magnitude). The workflow refuses to start on battery and records the active power plan.

## Lifecycle Analysis (RQ2) — Is Alpine the Preferable Image?

- Question: Under which deployment scenarios is the Alpine JRE image the preferable runtime image for Spring Boot microservices?
- Input: the measurements of Measurement Study 1 — no additional measurement runs
- Method: lifecycle cost model per scenario, break-even analysis, Pareto analysis

"Preferable" depends on what matters in a given situation: a smaller image saves transfer time and storage on every deployment, while slower startup, higher CPU cost or lower throughput costs time and resources on every start and during operation. The answer is therefore expected to be conditional rather than yes or no — the break-even point between the scenarios is the actual result. The main comparison is Alpine JRE vs. Ubuntu JRE (the only difference being the distribution), with the JDK and single-stage variants as reference points, and the Ubuntu JRE image with `MALLOC_ARENA_MAX=2` as a tuned alternative.

The analysis combines the measurements into the cost a team actually experiences over a period:

```
Cost per period = (number of builds          × cold build time, Study 1 Part A)
                + (number of deployments     × (transfer time ~ compressed image size + startup time), Part A)
                + (operating hours           × memory reserved per container, Parts B and C)
                + (requests served           × CPU time per request at the expected concurrency, Part B)
```

The model is evaluated for two to three realistic scenarios, for example:

| Scenario | Builds | Deployments | Operation | Load |
|---|---|---|---|---|
| Active development | 50 per day | 5 per day | short-lived test environments | low (10–25 users) |
| Stable production | 1 per week | 1 per week | 24/7, many replicas | moderate to high (50–200 users) |
| Autoscaling burst | few | many starts per day (scale-out) | short-lived replicas | high (200–400 users) |

Results:
- **Break-even points** — for example, from how many deployments per week the smaller Alpine image pays off if it starts more slowly or needs more CPU, and at which concurrency level the Ubuntu image (default or with `MALLOC_ARENA_MAX=2`) becomes preferable.
- **Pareto analysis** — which images are not dominated across all metrics (image size, build time, startup time, memory, CPU per request, throughput, p99 at each concurrency level), in particular whether Alpine JRE or Ubuntu JRE is ever dominated by the other.

The cost components have different units (seconds, MB, MiB-hours, CPU-seconds). They are reported side by side and combined only where a common unit is justified (e.g. time spent waiting for builds, transfers and starts, or the number of containers that fit on a node of a given size); the scenario parameters are assumptions and are reported with a sensitivity analysis rather than as a single answer.

## Reproducibility & CI/CD

Measurement Study 1 is executed via a GitHub Actions workflow running on a self-hosted runner registered to the thesis repository. The self-hosted runner is the author's laptop (Windows 11 Home, Docker Desktop), used exclusively for the measurements while they run: it runs on mains power, other containers are stopped (the workflow refuses to start otherwise) and no other applications run (including cloud sync clients such as Google Drive). All jobs run sequentially on this machine (`.github/workflows/experiments.yml`, started by hand) as separate workflow runs: `images` (Part A, about 5.5 hours), `load` (Part B, about 6 hours), `soak` (Part C, about 5 hours), `nmt` (Part D, about 1 hour), `gc` (Part E, about 4 hours) and `gcdetail` (Part E detail runs, under 1 hour); about 23 hours in total, run overnight over four nights; `test` is a short trial run of all phases. Each run records the host environment (Windows, CPU, RAM, Docker, tool versions) in `environment.txt` next to the measurements. The build cache is pruned automatically after every timed build, because the laptop has limited disk space. Limitations of this setup: Docker Desktop runs the containers in a WSL2 Linux VM (16 CPUs, 7.6 GiB memory) rather than natively, the hardware is a consumer laptop, and dependencies are downloaded over a home network connection. An Azure VM was evaluated first but could not be provisioned within the student subscription (`VM.md`).

**Infrastructure:**

- Docker Compose: postgres:16.15-alpine + rabbitmq:4.1.8-management-alpine started before measurement phases; company-service is additionally started for the load test (used once per run to seed the scores)
- Spring Boot Actuator: /actuator/health as startup probe; /actuator/prometheus for the JVM-reported application ready time, heap, thread and GC metrics
- Memory measured via `docker stats --no-stream` and the kernel's process status (`/proc/1/status`); CPU time via `/proc/1/stat`; no Prometheus or Grafana required
- Load test executed via k6 v2.4.0 (single binary); the measure-phase results are read from k6's end-of-test summary (JSON)
- Image size measured via a local registry (registry:2.8.3)
- Frontend (Nginx) is an UI profile; not part of the study

Pinned infrastructure images (base images of the variants: see `Tools Versions.md`):

| Tag | Pinned? |
|---|---|
| postgres:16.15-alpine | ✅ exact version |
| rabbitmq:4.1.8-management-alpine | ✅ exact version |
| prom/prometheus:v3.16.0 | ✅ exact version (not used for measurements) |
| grafana/grafana:13.3.0 | ✅ exact version (not used for measurements) |
| registry:2.8.3 | ✅ exact version (local registry for the size measurement) |
| nginx:1.27-alpine | ❌ not pinned (frontend, not part of the study) |

## Key literature:

1. Cito, J., Schermann, G., Wittern, J. E., Leitner, P., Zumberi, S., & Gall, H. C. (2017). An Empirical Analysis of the Docker Container Ecosystem on GitHub. In Proceedings of the 2017 IEEE/ACM 14th International Conference on Mining Software Repositories (MSR '17), pp. 323–333. IEEE.
2. Rosa, G., Scalabrino, S., & Oliveto, R. (2022). Assessing and Improving the Quality of Docker Artifacts. IEEE/ACM International Conference on Software Engineering (ICSE Doctoral Symposium / Summary).
3. Rastogi, V., Davidson, D., De Carli, L., Jha, S., & McDaniel, P. (2017). Cimplifier: Automatically Debloating Containers. In Proceedings of the 2017 11th Joint Meeting of the European Software Engineering Conference and the ACM SIGSOFT Symposium on the Foundations of Software Engineering (ESEC/FSE '17), pp. 476–486. ACM.
4. Zhu, Z., Chen, T., Liu, C., Liu, H., Song, Q., Xu, Z., & Liu, Y. (2025). Doctor: Optimizing Container Rebuild Efficiency by Instruction Re-Orchestration. In Proceedings of the 2025 ACM SIGSOFT International Symposium on Software Testing and Analysis (ISSTA '25), pp. 1–24. ACM.
5. Durieux, T. (2024). Empirical Study of the Docker Smells Impact on the Image Size. In Proceedings of the 2024 IEEE/ACM 46th International Conference on Software Engineering (ICSE '24), pp. 2568–2579. IEEE/ACM.
6. Badisa, N., Grandhi, J. K., Kallam, L., Eda, M. R., Srinivasu, N., & Bulla, S. (2023). Efficient Docker Image Optimization using Multi-Stage Builds and Nginx for Enhanced Application Deployment. Research Square Preprint, DOI: 10.21203/rs.3.rs-3276965/v1.
7. Rosa, G., Zappone, F., Scalabrino, S., & Oliveto, R. (2024). Fixing Dockerfile Smells: An Empirical Study. Empirical Software Engineering, Vol. 29, Art. 108, Springer.
8. Fachrudin, M. R., & Affandi, A. S. (2025). Implementation and Analysis of Container Image Optimization Using Alpine Linux and Multi-Stage Builds. TEKNIKA Journal, Vol. 14, No. 1, pp. 9–18. DOI: 10.34148/teknika.v14i1.1118.
9. Henkel, J., Bird, C., Lahiri, S. K., & Reps, T. (2020). Learning from, Understanding, and Supporting DevOps Artifacts for Docker. In Proceedings of the 2020 IEEE/ACM 42nd International Conference on Software Engineering (ICSE '20), pp. 38–49. ACM.
10. Rastogi, V., Niddodi, C., Mohan, S., & Jha, S. (2017). New Directions for Container Debloating. In Proceedings of the 2017 Workshop on Forming an Ecosystem Around Software Transformation (FEAST '17 / ESEC/FSE), pp. 51–56. ACM.
11. Rosa, G., Guglielmi, E., Iannone, M., Scalabrino, S., & Oliveto, R. (2025). Mining and Measuring the Impact of Change Patterns for Improving the Size and Build Time of Docker Images. Empirical Software Engineering, Vol. 30, Art. 150, Springer. DOI: 10.1007/s10664-025-10680-8.
12. Wu, Y., Zhang, Y., Wang, T., & Wang, H. (2022). Understanding and Predicting Docker Build Duration. In Proceedings of the 37th IEEE/ACM International Conference on Automated Software Engineering (ASE '22), pp. 1–12. ACM.
13. Tjiong, B. (2023). The impact of base image selection on the energy efficiency of containerized applications in Docker. MSc thesis, Delft University of Technology. Available at http://resolver.tudelft.nl/uuid:1166da2a-a62d-4b53-baa3-08e6e107053b.
14. Barba Roque, E., Cruz, L., & Durieux, T. (2025). Unveiling the Energy Vampires: A Methodology for Debugging Software Energy Consumption. In Proceedings of the 2025 IEEE/ACM 47th International Conference on Software Engineering (ICSE '25), pp. 2406–2418. IEEE/ACM. DOI: 10.1109/ICSE55347.2025.00118.

## Open Questions

1. **Is the single-stage variant needed in Measurement Study 1?**
   - Current setup of the single-stage variant: it is built on a self-built image that adds Maven 3.9.16 (SHA-512 verified) to `eclipse-temurin:21.0.13_9-jdk`, so it shares OS (Ubuntu 26.04) and JDK (Temurin 21.0.13) with the multi-stage JDK variant; the official `maven` image was not used because it runs on Ubuntu 24.04 with Temurin 21.0.12.1. The build image is created once before the measurement and is not timed. The final image contains the JDK, Maven, the sources and all downloaded dependencies.
   - For keeping it: it is the baseline that most literature on multi-stage builds compares against (e.g. Fachrudin & Affandi, 2025), and it shows the size and startup cost of shipping Maven, sources and downloaded dependencies. Without it, the step "single → multi-JDK" disappears from the size comparison.
   - For dropping it: its result (a much larger image) is well known and largely predictable; the open question of this thesis lies between the multi-stage runtime images, especially Ubuntu JRE vs. Alpine JRE.
   - Current decision (middle way): it is kept for image size, build time, startup and idle memory (Part A), and excluded from the runtime part (Parts B–D), which compares only the two JRE images and the arena control.
