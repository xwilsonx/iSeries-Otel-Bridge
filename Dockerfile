# Use a multi-stage build for efficiency
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# Download dependencies first to cache this layer
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/iseries-otel-bridge-1.0-SNAPSHOT.jar app.jar

# Create a directory for logs if needed
RUN mkdir -p /var/log/iseries

# Expose port (though strictly not needed for batch processing, good for health checks/metrics)
EXPOSE 8080

# Environment variables with defaults
ENV JAVA_OPTS=""
ENV OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:4317"
ENV INPUT_FILE_PATH_TAIL=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
