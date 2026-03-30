# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

iSeries OpenTelemetry Bridge is a Spring Batch application that bridges legacy iSeries (AS/400) application logs to modern OpenTelemetry observability platforms. It tails log files, parses them using Grok patterns, injects trace context, and exports structured logs to OTLP-compliant backends.

## Build Commands

```bash
# Build and run tests
mvn clean package

# Run all tests
mvn test

# Run single test class
mvn test -Dtest=GrokItemProcessorTest

# Run specific test method
mvn test -Dtest=GrokItemProcessorTest#testGrokParsing

# Apply code formatting (Google Java Style via Spotless)
mvn spotless:apply

# Run application
java -jar target/iseries-otel-bridge-1.0-SNAPSHOT.jar
```

## Architecture

The application uses a three-layer pipeline architecture:

1. **Ingestion Layer** (`src/main/java/com/iseries/otel/bridge/integration/FileTailingConfig.java`): Uses Spring Integration Tailer to tail log files in real-time
2. **Processing Layer** (`src/main/java/com/iseries/otel/bridge/batch/`): Spring Batch job with:
   - `MultilineLogItemReader`: Reads multiline log records
   - `GrokItemProcessor`: Parses log lines using Grok patterns
   - `MultilineRecordSeparatorPolicy`: Handles Java stack traces
3. **Export Layer** (`src/main/java/com/iseries/otel/bridge/batch/OtelItemWriter.java`): Converts structured data to OpenTelemetry LogRecords

Configuration in `BatchConfig.java` defines the chunk-oriented step with fault tolerance (skip limit of 10).

## Key Configuration

Environment variables (notable ones):
- `INPUT_FILE_PATH_TAIL`: Log file path to tail
- `PARSER_GROK_PATTERN`: Grok pattern for parsing (default: `%{GREEDYDATA:message}`)
- `OTEL_EXPORTER_OTLP_ENDPOINT`: OTLP endpoint (default: `http://localhost:4317`)
- `OTEL_SERVICE_NAME`: Service name in OTel backend

## Technology Stack

- Java 17 (Eclipse Temurin)
- Spring Boot 3.2, Spring Batch, Spring Integration
- OpenTelemetry SDK 1.34.1
- Java Grok 0.1.9
- Maven (requires Maven 3.8+, no wrapper included)

## Code Style

- Spotless with Google Java Style (AOSP) is enforced during `validate` phase
- JaCoCo requires 80% line coverage
- Follow AGENTS.md for detailed naming conventions and formatting

## Test Resources

Test log samples are in `src/test/resources/` covering various formats (iseries_joblog.log, syslog_rfc5424.log, json_structured.log, etc.)
