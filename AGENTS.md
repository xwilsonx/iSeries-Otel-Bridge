# AGENTS.md - Development Guide for AI Coding Agents

This guide provides essential information for AI coding agents working with this Java/Spring Boot project that bridges legacy iSeries logs to OpenTelemetry.

## Build Commands

### Maven Build Commands
```bash
# Full build and test cycle
./mvnw clean install

# Compile only
./mvnw compile

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=GrokItemProcessorTest

# Run a specific test method
./mvnw test -Dtest=GrokItemProcessorTest#testGrokParsing

# Package the application (creates JAR)
./mvnw package

# Run the application
./mvnw spring-boot:run

# Clean build artifacts
./mvnw clean
```

Note: This project uses Maven but doesn't have the Maven wrapper checked in. Install Maven 3.8+ locally.

## Test Commands

### Running Tests
```bash
# Run all unit and integration tests
mvn test

# Run tests with specific profile
mvn test -Pintegration-test

# Run tests and generate coverage report
mvn test jacoco:report

# Run a single test class
mvn test -Dtest=OtelItemWriterTest

# Run a specific test method
mvn test -Dtest=OtelItemWriterTest#testWriteSuccess

# Run tests with debug output
mvn test -X
```

### Test Structure
- Unit tests: Located in `src/test/java/` mirroring the package structure of `src/main/java/`
- Integration tests: Also in `src/test/java/` but may require specific naming conventions
- Test resources: `src/test/resources/` contains sample data files

## Code Style Guidelines

### Java Language Standards
- Java 17+ features are supported (as specified in pom.xml)
- Follow standard Java naming conventions:
  - Classes: UpperCamelCase (e.g., `GrokItemProcessor`)
  - Methods: lowerCamelCase (e.g., `processItem()`)
  - Constants: UPPER_SNAKE_CASE (e.g., `MAX_BUFFER_SIZE`)
  - Variables: lowerCamelCase (e.g., `itemCount`)

### Imports Organization
1. Java standard library imports (java.*, javax.*)
2. Third-party library imports (org.*, com.*, etc.)
3. Spring Framework imports
4. Project-specific imports
5. Static imports at the bottom

Separate each group with a blank line. Alphabetize within groups.

### Formatting Standards
- Indentation: 4 spaces (no tabs)
- Line length: 120 characters max
- Braces: Opening brace on same line, closing brace on new line
- Spaces: After commas, around operators, after control statements

### Types and Declarations
- Use meaningful type names that clearly express intent
- Prefer interfaces over concrete classes for variable declarations
- Use Lombok annotations (@Slf4j, @Data, @Getter/@Setter) where appropriate
- All classes should have proper Javadoc comments for public methods

### Naming Conventions
- Package names: lowercase, hyphenated words (e.g., `com.iseries.otel.bridge.batch`)
- Class names: nouns, upper camel case
- Method names: verbs, lower camel case
- Variable names: descriptive, lower camel case
- Constants: uppercase with underscores

### Error Handling
- Use SLF4J for logging with appropriate levels (trace, debug, info, warn, error)
- Never log and throw - choose one or the other
- Include contextual information in log messages
- Use specific exception types rather than generic Exception
- Handle null values explicitly with proper validation

### Spring-Specific Guidelines
- Use constructor injection over field injection
- Annotate components appropriately (@Component, @Service, @Repository)
- Use @RequiredArgsConstructor for dependency injection with Lombok
- Configure beans in dedicated configuration classes
- Use @Slf4j for logging in Spring components

## Architecture Overview

This is a Spring Batch application that processes legacy iSeries logs and converts them to OpenTelemetry format:

1. **Ingestion Layer**: Reads log files using Spring Batch ItemReaders
2. **Processing Layer**: Parses log lines using Grok patterns and transforms to structured data
3. **Export Layer**: Writes structured data as OpenTelemetry LogRecords

Key components:
- `GrokItemProcessor`: Parses log lines using Grok patterns
- `OtelItemWriter`: Converts structured data to OpenTelemetry LogRecords
- `MultilineLogItemReader`: Handles multiline log records
- `FileTailingConfig`: Enables real-time file tailing

## Dependencies and Libraries

Major libraries used:
- Spring Boot 3.2.2
- Spring Batch for processing pipeline
- Spring Integration for file tailing
- OpenTelemetry SDK 1.34.1 for telemetry export
- Java Grok 0.1.9 for log parsing
- Lombok for reducing boilerplate code

## Testing Approach

Testing follows standard Java/JUnit 5 practices:
- Unit tests focus on individual components in isolation
- Integration tests verify interactions between components
- Mock objects are used to isolate units under test
- AssertJ or standard JUnit assertions are used for verification
- Test methods follow the naming pattern: `test[MethodUnderTest]_[Condition]`

Example test structure:
```java
@Test
void testGrokParsing_withValidLogLine_returnsParsedFields() {
    // Given
    String pattern = "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
    GrokItemProcessor processor = new GrokItemProcessor(pattern);
    
    // When
    String logLine = "2023-10-27 10:00:00 INFO Application started";
    Map<String, Object> result = processor.process(logLine);
    
    // Then
    assertThat(result).containsEntry("timestamp", "2023-10-27 10:00:00");
    assertThat(result).containsEntry("level", "INFO");
}
```

## CI/CD Considerations

- Ensure all tests pass before committing code
- Follow semantic versioning for releases
- Maintain backward compatibility when possible
- Document breaking changes in release notes

## Debugging Tips

- Enable debug logging with `-Dlogging.level.com.iseries.otel=DEBUG`
- Use the sample.log file in test resources for consistent test data
- Run individual test methods to isolate issues
- Check the OpenTelemetry collector logs for export issues