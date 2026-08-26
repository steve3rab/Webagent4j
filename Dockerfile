# syntax=docker/dockerfile:1

FROM mcr.microsoft.com/playwright/java:v1.62.0-noble AS playwright-java21

ARG DEBIAN_FRONTEND=noninteractive

USER root

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/* \
    && ln -sfn \
       "/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)" \
       /opt/java21

ENV JAVA_HOME=/opt/java21
ENV PATH="${JAVA_HOME}/bin:${PATH}"

FROM playwright-java21 AS base

WORKDIR /workspace
RUN chown pwuser:pwuser /workspace

COPY --chown=pwuser:pwuser . .
RUN chmod +x mvnw

USER pwuser

FROM base AS test

RUN java -version \
    && ./mvnw --batch-mode --no-transfer-progress clean verify

FROM base AS robustness

RUN ./mvnw --batch-mode --no-transfer-progress -Probustness verify

FROM base AS build

RUN ./mvnw --batch-mode --no-transfer-progress -Pdistribution -DskipTests package \
    && cp webagent4j-cli/target/webagent4j-cli-*.jar /workspace/webagent4j.jar

FROM playwright-java21 AS runtime

ENV HOME=/home/pwuser

WORKDIR /app

COPY --from=build \
    --chown=pwuser:pwuser \
    /workspace/webagent4j.jar \
    /app/webagent4j.jar

USER pwuser

ENTRYPOINT ["java", "-jar", "/app/webagent4j.jar"]
CMD ["--help"]