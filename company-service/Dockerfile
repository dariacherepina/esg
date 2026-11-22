FROM esg-maven:3.9.16-temurin-21.0.13

WORKDIR /build
COPY pom.xml .
COPY company-service/pom.xml ./company-service/
RUN mvn -B -q -f company-service/pom.xml dependency:go-offline
COPY company-service/src ./company-service/src

RUN mvn -B -q -f company-service/pom.xml package -DskipTests

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/build/company-service/target/company-service-0.0.1-SNAPSHOT.jar"]
