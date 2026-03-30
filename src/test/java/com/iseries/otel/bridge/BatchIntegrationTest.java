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
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class BatchIntegrationTest {

    @Autowired private JobLauncher jobLauncher;

    @Autowired private Job logConversionJob;

    @Autowired private SdkLoggerProvider sdkLoggerProvider;

    // We capture exported logs here
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

    @Test
    void testJobExecution() throws Exception {
        // Clear logs
        EXPORTED_LOGS.clear();

        File sampleFile = new ClassPathResource("sample.log").getFile();
        String samplePath = sampleFile.getAbsolutePath();
        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", samplePath)
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong(
                                "timestamp",
                                System.currentTimeMillis()) // Unique param to force run
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());

        // Flush logs
        sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        // Verify logs were exported
        // Expected: 4 logs (one multiline, 3 single lines)
        // With MultilineLogItemReader, we expect exactly 4 logs.
        // The 3rd log should be multiline.
        assertEquals(4, EXPORTED_LOGS.size(), "Should have exported exactly 4 logs");

        // specific check for multiline
        boolean foundMultiline =
                EXPORTED_LOGS.stream()
                        .anyMatch(
                                log ->
                                        log.getBody().asString().contains("\n")
                                                || log.getBody().asString().contains("\r"));
        assertTrue(foundMultiline, "Should have verified at least one multiline log");

        System.out.println("\n=== [SIMULATION] EXPORTED OTEL LOGS ===");
        System.out.println(
                "These logs were successfully converted and passed to the OpenTelemetry Exporter:");
        System.out.println(
                "--------------------------------------------------------------------------------");
        for (LogRecordData log : EXPORTED_LOGS) {
            System.out.println("Timestamp: " + log.getTimestampEpochNanos() + " (nanos)");
            System.out.println(
                    "Severity: " + log.getSeverityText() + " (" + log.getSeverity() + ")");
            System.out.println("Body: " + log.getBody().asString());
            System.out.println("Attributes: " + log.getAttributes());
            System.out.println(
                    "--------------------------------------------------------------------------------");
        }
        System.out.println("Total Logs Exported: " + EXPORTED_LOGS.size());
    }
}
