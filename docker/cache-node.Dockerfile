# One standalone cache node (pure Java, no Spring). Build context = repository root.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY cache-core/pom.xml cache-core/
COPY management-api/pom.xml management-api/
COPY cache-core/src cache-core/src
RUN mvn -q -B -pl cache-core -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cache
WORKDIR /app
COPY --from=build /build/cache-core/target/cache-core-1.0.0.jar /app/cache-node.jar
USER cache
EXPOSE 7001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/cache-node.jar"]
