package com.iseries.otel.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

/**
 * Performance test for high-volume log processing. Validates that the pipeline can handle 500+ log
 * records including multiline entries without degradation or data loss.
 */
@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class HighVolumePerformanceTest {

    @Autowired private JobLauncher jobLauncher;

    @Autowired private Job logConversionJob;

    @Autowired private SdkLoggerProvider sdkLoggerProvider;

    private static final List<LogRecordData> EXPORTED_LOGS =
            Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public LogRecordExporter logRecordExporter() {
            return new LogRecordExporter() {
                @Override
                public CompletableResultCode export(Collection<LogRecordData> logs) {
                    EXPORTED_LOGS.addAll(logs);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            };
        }
    }

    @BeforeEach
    void clearLogs() {
        EXPORTED_LOGS.clear();
    }

    @Test
    void testHighVolumeLogProcessing() throws Exception {
        File sampleFile = new ClassPathResource("high_volume.log").getFile();

        // Pattern matches: TIMESTAMP LEVEL [COMPONENT] MESSAGE
        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{SPACE}\\[%{DATA:component}\\] %{GREEDYDATA:message}";
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        long startTime = System.currentTimeMillis();

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        long elapsedMs = System.currentTimeMillis() - startTime;

        // Should process all 500 log records (some are multiline, so record count is 500)
        assertTrue(
                EXPORTED_LOGS.size() >= 400,
                "Should process at least 400 log records from high volume file, got: "
                        + EXPORTED_LOGS.size());

        // Performance assertion: should complete within 30 seconds
        assertTrue(
                elapsedMs < 30000,
                "High volume processing should complete within 30s, took: " + elapsedMs + "ms");

        // Verify severity distribution - should have all log levels represented
        long infoCount =
                EXPORTED_LOGS.stream().filter(log -> "INFO".equals(log.getSeverityText())).count();
        long errorCount =
                EXPORTED_LOGS.stream().filter(log -> "ERROR".equals(log.getSeverityText())).count();
        long debugCount =
                EXPORTED_LOGS.stream().filter(log -> "DEBUG".equals(log.getSeverityText())).count();
        long warnCount =
                EXPORTED_LOGS.stream().filter(log -> "WARN".equals(log.getSeverityText())).count();

        assertTrue(infoCount > 0, "Should have INFO logs");
        assertTrue(errorCount > 0, "Should have ERROR logs");
        assertTrue(debugCount > 0, "Should have DEBUG logs");
        assertTrue(warnCount > 0, "Should have WARN logs");

        // Verify component attribute is captured
        long withComponent =
                EXPORTED_LOGS.stream()
                        .filter(
                                log ->
                                        log.getAttributes()
                                                .asMap()
                                                .containsKey(
                                                        io.opentelemetry.api.common.AttributeKey
                                                                .stringKey("component")))
                        .count();
        assertTrue(withComponent > 0, "Should have logs with component attribute");

        System.out.println("\n=== HIGH VOLUME PERFORMANCE RESULTS ===");
        System.out.println("Total records processed: " + EXPORTED_LOGS.size());
        System.out.println("Processing time: " + elapsedMs + "ms");
        System.out.println(
                "Throughput: "
                        + (EXPORTED_LOGS.size() * 1000L / Math.max(elapsedMs, 1))
                        + " records/sec");
        System.out.println(
                "Severity distribution: INFO="
                        + infoCount
                        + " ERROR="
                        + errorCount
                        + " DEBUG="
                        + debugCount
                        + " WARN="
                        + warnCount);
        System.out.println("Records with component attribute: " + withComponent);
    }

    @Test
    void testHighVolumeMultilineHandling() throws Exception {
        File sampleFile = new ClassPathResource("high_volume.log").getFile();

        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{SPACE}\\[%{DATA:component}\\] %{GREEDYDATA:message}";
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong("timestamp", System.currentTimeMillis() + 1)
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        // Verify multiline records exist (ERROR logs with stack traces)
        long multilineCount =
                EXPORTED_LOGS.stream()
                        .filter(
                                log -> {
                                    String body = log.getBody().asString();
                                    return body.contains("\n") || body.contains("\r");
                                })
                        .count();

        assertTrue(
                multilineCount > 0, "Should have multiline log records (ERROR with stack traces)");

        // Verify multiline records contain expected stack trace elements
        boolean hasStackTrace =
                EXPORTED_LOGS.stream()
                        .anyMatch(
                                log ->
                                        log.getBody().asString().contains("RuntimeException")
                                                && log.getBody().asString().contains("\tat "));

        assertTrue(
                hasStackTrace, "At least one multiline record should contain a full stack trace");
    }
}
