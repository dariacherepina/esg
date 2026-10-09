# Single-stage build — the UNOPTIMISED baseline for the container experiment.
# Everything (JDK, Maven, downloaded dependencies, sources, build output) ends up in the final image.
# No JVM flags: the JVM sizes its heap from defaults.
#
# Build from the repo root:
#   docker build -f company-service/Dockerfile -t company-service:single .

FROM maven:3.9-eclipse-temurin-21

WORKDIR /build
COPY pom.xml .
COPY company-service ./company-service

RUN mvn -B -q -pl company-service -am package -DskipTests

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/build/company-service/target/company-service-0.0.1-SNAPSHOT.jar"]
