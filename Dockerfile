# Multi-stage Dockerfile for Trade Imports Animals Backend
# Uses Amazon Corretto 25 (matches project configuration)

################################################################################
# Stage 1: Build
# - Full JDK for compilation
# - Run tests
# - Create executable JAR
################################################################################
FROM amazoncorretto:25-alpine AS build

WORKDIR /build

# Install Maven
RUN apk add --no-cache maven

# Copy pom.xml first for dependency caching
COPY pom.xml .

# Download dependencies (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build without running tests
# Tests are run separately in CI/CD pipeline and locally
# (Testcontainers requires Docker, which isn't available inside Docker build)
RUN mvn clean package -DskipTests -B

################################################################################
# Stage 2: Development
# - Includes development tools
# - For local development with docker compose
################################################################################
FROM amazoncorretto:25-alpine AS development

WORKDIR /app

# Install curl and bash for development
RUN apk add --no-cache curl bash

# Copy JAR from build stage
COPY --from=build /build/target/*.jar app.jar

# Non-root user
USER nobody

# Default port (configurable via PORT env var)
EXPOSE 8085

# Health check (for local docker run)
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8085/health || exit 1

# Start application
ENTRYPOINT ["java", "-jar", "app.jar"]

################################################################################
# Stage 3: Production
# - Minimal runtime image
# - Meets all CDP platform requirements
################################################################################
FROM amazoncorretto:25-alpine AS production

WORKDIR /app

# CDP PLATFORM REQUIREMENTS:
# - curl: Required for ECS healthcheck (curl -f http://localhost:8085/health || exit 1)
# - shell: Required for CMD-SHELL healthcheck (/bin/sh -c)
RUN apk add --no-cache curl

# Copy JAR from build stage
COPY --from=build /build/target/*.jar app.jar

# Non-root user (CDP security requirement)
USER nobody

# Port 8085 (CDP platform standard)
EXPOSE 8085

# Health check configuration
# Note: ECS configures this at platform level, but including for local testing
# ECS uses: ["CMD-SHELL", "curl -f http://localhost:8085/health || exit 1"]
# - Interval: 30 seconds
# - Timeout: 5 seconds
# - Retries: 3 (max 95 seconds before restart)
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD curl -f http://localhost:8085/health || exit 1

# ENTRYPOINT with no parameters (CDP requirement)
# ECS doesn't support runtime arguments
ENTRYPOINT ["java", "-jar", "app.jar"]
