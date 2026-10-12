# Single-stage build — the UNOPTIMISED baseline for the container experiment.
# Everything (JDK, Maven, downloaded dependencies, sources, build output) ends up in the final image.
# No JVM flags: the JVM sizes its heap from defaults.
#
# Build from the repo root:
#   docker build -f report-service/Dockerfile -t report-service:single .

FROM maven:3.9-eclipse-temurin-21

WORKDIR /build
COPY pom.xml .
COPY report-service ./report-service

# -f builds the module via its own POM; the parent is read only for inheritance, so the other
# modules listed in the root POM don't need to be in the build context.
RUN mvn -B -q -f report-service/pom.xml package -DskipTests

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/build/report-service/target/report-service-0.0.1-SNAPSHOT.jar"]
