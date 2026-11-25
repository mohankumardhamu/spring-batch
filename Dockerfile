# syntax=docker/dockerfile:1
# Single multi-stage Dockerfile shared by all services.
# Build one service with: docker build --build-arg SERVICE=user-service -t user-service .
ARG SERVICE

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /workspace
COPY pom.xml .
COPY user-service/pom.xml user-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY batch-service/pom.xml batch-service/pom.xml
COPY ${SERVICE}/src ${SERVICE}/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl ${SERVICE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/${SERVICE}/target/${SERVICE}.jar app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
