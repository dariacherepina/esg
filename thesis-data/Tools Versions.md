# Tools Versions

All versions read directly from the system or the images on 2026-11-19 (commit `67ba918`).
Container images are pinned by digest in the repository, so the image contents are fixed. The host tools apply to the **development PC**; the self-hosted runner laptop must be recorded separately before the final runs.

## Base images of the measured variants

| Image (pinned) | Digest | Java | OS | libc |
|---|---|---|---|---|
| `maven:3.9.16-eclipse-temurin-21` | `sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320` | Temurin 21.0.12.1, Maven 3.9.16 | Ubuntu 24.04.5 LTS | glibc 2.39 |
| `eclipse-temurin:21.0.13_9-jdk` | `sha256:db5cc6341a1d7bc40cdb51e1f0d9824f3bb2cc8107d29da421dc4df7d11f05f1` | Temurin 21.0.13+9 (JDK) | Ubuntu 26.04.1 LTS | glibc 2.43 |
| `eclipse-temurin:21.0.13_9-jre` | `sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a` | Temurin 21.0.13+9 (JRE) | Ubuntu 26.04.1 LTS | glibc 2.43 |
| `eclipse-temurin:21.0.13_9-jre-alpine` | `sha256:ac05c3c42b7c2f1a52c9bd52f01041fa1958231b15ebeae7420429cbc5baf474` | Temurin 21.0.13+9 (JRE) | Alpine Linux 3.24.4 | musl 1.2.6 |

| Variant | Build stage | Runtime image |
|---|---|---|
| single | – | `esg-maven:3.9.16-temurin-21.0.13` (built locally: `eclipse-temurin:21.0.13_9-jdk` + Maven 3.9.16; Ubuntu 26.04.1, Temurin 21.0.13) |
| multi-jdk | `maven:3.9.16-eclipse-temurin-21` | `eclipse-temurin:21.0.13_9-jdk` |
| multi-jre | `maven:3.9.16-eclipse-temurin-21` | `eclipse-temurin:21.0.13_9-jre` |
| multi (Alpine) | `maven:3.9.16-eclipse-temurin-21` | `eclipse-temurin:21.0.13_9-jre-alpine` |

Note: the official Maven image runs on Java 21.0.12.1 / Ubuntu 24.04. It is only used to compile the JAR in the build stage of the multi-stage variants. The single-stage variant runs on a self-built image with Maven 3.9.16 (archive SHA-512 `831a8591…358b6f6`) on `eclipse-temurin:21.0.13_9-jdk`, so single and multi-jdk share Ubuntu 26.04.1 and Temurin 21.0.13.

## Application

| Component | Version |
|---|---|
| Java (language level) | 21 |
| Spring Boot | 3.5.6 |
| Services | company-service, esg-score-service, portfolio-service, report-service (0.0.1-SNAPSHOT) |

## Infrastructure images (Docker Compose)

| Image | Role in the experiments |
|---|---|
| `postgres:16.15-alpine` | database for the startup and load phases |
| `rabbitmq:4.1.8-management-alpine` | message broker for the startup and load phases |
| `prom/prometheus:v3.16.0` | not used for measurements |
| `grafana/grafana:13.3.0` | not used for measurements |
| `nginx:1.27-alpine` (frontend) | not used for measurements, not pinned |
| `registry:2.8.3` | local registry for the compressed image size |

## Measurement and analysis tools

| Tool | Version |
|---|---|
| k6 | v2.4.0 (commit dd9395906d, go1.26.11, windows/amd64) |
| Hadolint | v2.15.1 (`hadolint/hadolint@sha256:32dac94127fd60b7b7e3fbfc65e1383b9b5e25c9bfd7b8536de7a539fe68a12d`) |
| Python | 3.10.6 |
| SciPy | 1.14.1 |
| Windows PowerShell | 5.1.26100.9711 |

## Host (development PC)

| Component | Version |
|---|---|
| OS | Windows 11 Home 10.0.26200 (build 26200) |
| Docker Desktop | 4.40.0 (187762) |
| Docker Engine (client and server) | 28.0.4 |
| containerd / runc | 1.7.26 / 1.2.5 |
| Docker Compose | 2.34.0-desktop.1 |
| Docker Buildx | v0.22.0-desktop.1 |
| Docker VM | WSL2, kernel 5.15.167.4-microsoft-standard-WSL2, linux/amd64, cgroup v1 |
| Docker VM resources | 16 CPUs, 7.6 GiB memory |
| Git | 2.37.1.windows.1 |
