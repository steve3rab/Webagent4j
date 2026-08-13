# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/playwright/java:v1.60.0-noble AS base
RUN apt-get update \
    && apt-get install --yes --no-install-recommends openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw

FROM base AS test
RUN ./mvnw --batch-mode --no-transfer-progress clean verify

FROM base AS build
RUN ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.60.0-noble
WORKDIR /app
COPY --from=build /workspace/webagent4j-cli/target/webagent4j-cli-0.1.0-SNAPSHOT.jar /app/webagent4j.jar
ENTRYPOINT ["java", "-jar", "/app/webagent4j.jar"]
CMD ["--help"]
