# syntax=docker/dockerfile:1.7
# One build stage compiles the whole Maven reactor; two small runtime images are cut from it.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY prism-common/pom.xml prism-common/
COPY prism-gateway/pom.xml prism-gateway/
COPY prism-control-plane/pom.xml prism-control-plane/
COPY prism-common/src prism-common/src
COPY prism-gateway/src prism-gateway/src
COPY prism-control-plane/src prism-control-plane/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS runtime-base
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 prism
WORKDIR /app
COPY data /app/data
# ONNX Runtime and the tokenizer unpack native libraries into the user's home on first use.
ENV HOME=/home/prism
USER prism

FROM runtime-base AS gateway
COPY --from=build /src/prism-gateway/target/prism-gateway.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS control-plane
COPY --from=build /src/prism-control-plane/target/prism-control-plane.jar /app/app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
