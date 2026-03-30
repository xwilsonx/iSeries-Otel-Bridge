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
 * Integration tests for structured log formats. Covers JSON structured logs and MDC/thread-context
 * log patterns.
 */
@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class StructuredLogFormatsTest {

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
    void testJsonStructuredLog() throws Exception {
        File sampleFile = new ClassPathResource("json_structured.log").getFile();

        // For JSON logs, each line is a self-contained JSON object.
        // We use a simple Grok pattern that captures the whole line as message,
        // since the Grok parser doesn't natively parse JSON.
        // In a real pipeline, a JSON-aware processor would be used instead.
        // Here we demonstrate that the pipeline handles one-line-per-record correctly.
        String grokPattern = "%{GREEDYDATA:message}";
        // Each JSON line starts with {
        String filePattern = "^\\{";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        assertEquals(6, EXPORTED_LOGS.size(), "Should have processed 6 JSON log entries");

        // Verify the raw JSON is preserved in the body
        LogRecordData firstLog = EXPORTED_LOGS.get(0);
        assertTrue(
                firstLog.getBody().asString().contains("\"level\":\"INFO\""),
                "Body should contain raw JSON with level");
        assertTrue(
                firstLog.getBody().asString().contains("Application startup completed"),
                "Body should contain the message");

        // Verify the error log has payment info preserved
        LogRecordData errorLog = EXPORTED_LOGS.get(4);
        assertTrue(
                errorLog.getBody().asString().contains("Payment processing failed"),
                "Error log body should contain payment failure");
        assertTrue(
                errorLog.getBody().asString().contains("txn-98765"),
                "Error log body should preserve transaction ID");
    }

    @Test
    void testMdcThreadContextLog() throws Exception {
        File sampleFile = new ClassPathResource("mdc_thread_context.log").getFile();

        // Pattern: TIMESTAMP [THREAD] LEVEL CLASS - requestId=... sessionId=... MESSAGE
        // We extract thread and class as attributes, plus the MDC-enriched message
        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} \\[%{DATA:thread}\\] %{LOGLEVEL:level} %{SPACE}%{DATA:logger} - %{GREEDYDATA:message}";
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
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        assertEquals(6, EXPORTED_LOGS.size(), "Should have processed 6 MDC log entries");

        // Verify thread name is captured as attribute
        LogRecordData secondLog = EXPORTED_LOGS.get(1);
        assertEquals(
                "http-nio-8080-exec-1",
                secondLog
                        .getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("thread"))
                        .toString());
        assertEquals("DEBUG", secondLog.getSeverityText());
        assertTrue(
                secondLog.getBody().asString().contains("requestId=req-002"),
                "Body should contain MDC requestId");

        // Verify multiline ERROR with stack trace
        LogRecordData errorLog = EXPORTED_LOGS.get(4);
        assertEquals("ERROR", errorLog.getSeverityText());
        assertTrue(
                errorLog.getBody().asString().contains("Failed to send email notification"),
                "Error body should contain notification failure message");
        assertTrue(
                errorLog.getBody().asString().contains("java.net.ConnectException"),
                "Error body should contain exception class");
        assertTrue(
                errorLog.getBody().asString().contains("SmtpClient.connect"),
                "Error body should contain stack trace frames");

        // Verify logger class is captured
        assertEquals(
                "com.myapp.service.NotificationService",
                errorLog.getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("logger"))
                        .toString());
    }
}
