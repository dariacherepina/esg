FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

WORKDIR /build
COPY pom.xml .
COPY portfolio-service ./portfolio-service

RUN mvn -B -q -f portfolio-service/pom.xml package -DskipTests

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/build/portfolio-service/target/portfolio-service-0.0.1-SNAPSHOT.jar"]
