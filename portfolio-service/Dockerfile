FROM esg-maven:3.9.16-temurin-21.0.13

WORKDIR /build
COPY pom.xml .
COPY portfolio-service/pom.xml ./portfolio-service/
RUN mvn -B -q -f portfolio-service/pom.xml dependency:go-offline
COPY portfolio-service/src ./portfolio-service/src

RUN mvn -B -q -f portfolio-service/pom.xml package -DskipTests

EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/build/portfolio-service/target/portfolio-service-0.0.1-SNAPSHOT.jar"]
