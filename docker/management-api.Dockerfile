# Spring Boot management API / cache gateway. Build context = repository root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY cache-core/pom.xml cache-core/
COPY management-api/pom.xml management-api/
COPY cache-core/src cache-core/src
COPY management-api/src management-api/src
RUN mvn -q -B -pl management-api -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /build/management-api/target/management-api-1.0.0.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
