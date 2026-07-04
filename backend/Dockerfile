# syntax=docker/dockerfile:1

# ---- Build stage: compile the Spring Boot fat JAR with the Maven wrapper ----
# JDK 25 (Temurin) matches <java.version>25</java.version> in pom.xml.
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Copy the wrapper + POM first and resolve dependencies as their own layer,
# so a source-only change doesn't re-download the world. The --mount cache
# keeps the local Maven repo (~/.m2) warm across builds (BuildKit).
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B dependency:go-offline

# Now the sources. Tests need Testcontainers (a Docker daemon we don't have
# in the build sandbox), so skip them here -- CI runs the test suite instead.
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests

# ---- Runtime stage: run the JAR on a slim JRE, as a non-root user ----
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S falcon && adduser -S falcon -G falcon
COPY --from=build /workspace/target/*.jar app.jar
USER falcon

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
