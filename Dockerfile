# syntax=docker/dockerfile:1
FROM mcr.microsoft.com/playwright/java:v1.60.0-noble AS build
WORKDIR /workspace
COPY . .
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM mcr.microsoft.com/playwright/java:v1.60.0-noble
WORKDIR /app
COPY --from=build /workspace/webagent4j-cli/target/webagent4j-cli-0.1.0-SNAPSHOT.jar /app/webagent4j.jar
ENTRYPOINT ["java", "-jar", "/app/webagent4j.jar"]
CMD ["--help"]
