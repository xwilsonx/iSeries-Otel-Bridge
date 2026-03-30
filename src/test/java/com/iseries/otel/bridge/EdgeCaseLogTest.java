package com.iseries.otel.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Integration tests for edge case log handling. Validates that the pipeline gracefully handles
 * empty messages, special characters, Unicode content, long lines, and unparseable input.
 */
@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class EdgeCaseLogTest {

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
    void testEdgeCaseLogParsing() throws Exception {
        File sampleFile = new ClassPathResource("edge_cases.log").getFile();

        // Standard pattern that will succeed for valid lines and fallback for others
        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(
                BatchStatus.COMPLETED,
                execution.getStatus(),
                "Job should complete even with edge case input");
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        // Should not crash - all records should be processed
        assertTrue(
                EXPORTED_LOGS.size() >= 8,
                "Should process at least 8 log entries, got: " + EXPORTED_LOGS.size());
    }

    @Test
    void testSpecialCharactersPreserved() throws Exception {
        File sampleFile = new ClassPathResource("edge_cases.log").getFile();

        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
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

        // Find the log with special characters (3rd entry, index 2)
        LogRecordData specialCharsLog = EXPORTED_LOGS.get(2);
        String body = specialCharsLog.getBody().asString();
        assertTrue(body.contains("<xml>"), "Should preserve angle brackets");
        assertTrue(body.contains("\"quotes\""), "Should preserve quotes");

        // Find the Unicode log (4th entry, index 3)
        LogRecordData unicodeLog = EXPORTED_LOGS.get(3);
        String unicodeBody = unicodeLog.getBody().asString();
        assertTrue(
                unicodeBody.contains("cafe") || unicodeBody.contains("café"),
                "Should handle Unicode content");
    }

    @Test
    void testEmptyMessageHandled() throws Exception {
        File sampleFile = new ClassPathResource("edge_cases.log").getFile();

        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong("timestamp", System.currentTimeMillis() + 2)
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        // The second log entry has an empty message (just "INFO  " with nothing after)
        // It should still be parsed and exported, just with empty or whitespace body
        LogRecordData emptyMsgLog = EXPORTED_LOGS.get(1);
        assertNotNull(emptyMsgLog, "Empty message log should still be exported");
        assertEquals("INFO", emptyMsgLog.getSeverityText());
    }

    @Test
    void testLongLineHandled() throws Exception {
        File sampleFile = new ClassPathResource("edge_cases.log").getFile();

        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong("timestamp", System.currentTimeMillis() + 3)
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        // The 5th log entry (index 4) has an extremely long message
        LogRecordData longLog = EXPORTED_LOGS.get(4);
        String body = longLog.getBody().asString();
        assertTrue(
                body.length() > 200,
                "Long message should be preserved, got length: " + body.length());
    }
}
