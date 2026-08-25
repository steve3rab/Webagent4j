# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/playwright/java:v1.62.0-noble AS base
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw

FROM base AS test
RUN ./mvnw --batch-mode --no-transfer-progress clean verify

FROM base AS robustness
RUN ./mvnw --batch-mode --no-transfer-progress -Probustness verify

FROM base AS build
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package \
    && cp webagent4j-cli/target/webagent4j-cli-*.jar /workspace/webagent4j.jar

FROM mcr.microsoft.com/playwright/java:v1.62.0-noble
WORKDIR /app
COPY --from=build /workspace/webagent4j.jar /app/webagent4j.jar
ENTRYPOINT ["java", "-jar", "/app/webagent4j.jar"]
CMD ["--help"]
