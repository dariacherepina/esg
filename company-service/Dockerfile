FROM maven:3.9-eclipse-temurin-21

WORKDIR /build
COPY pom.xml .
COPY company-service ./company-service

RUN mvn -B -q -f company-service/pom.xml package -DskipTests

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/build/company-service/target/company-service-0.0.1-SNAPSHOT.jar"]
