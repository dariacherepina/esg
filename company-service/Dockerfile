FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320

WORKDIR /build
COPY pom.xml .
COPY company-service/pom.xml ./company-service/
RUN mvn -B -q -f company-service/pom.xml dependency:go-offline
COPY company-service/src ./company-service/src

RUN mvn -B -q -f company-service/pom.xml package -DskipTests

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/build/company-service/target/company-service-0.0.1-SNAPSHOT.jar"]
