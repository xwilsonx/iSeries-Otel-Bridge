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

@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class CustomLogFormatsTest {

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
    void testComplexErrorLog() throws Exception {
        File sampleFile = new ClassPathResource("complex_error.log").getFile();

        // This pattern matches standard log lines: "TIMESTAMP LEVEL MESSAGE"
        // The message captures everything, including newlines for stack traces
        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";

        // This regex identifies the START of a new log record (lines beginning with YYYY-MM-DD)
        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

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

        assertEquals(4, EXPORTED_LOGS.size(), "Should have processed 4 log entries");

        // Verify the first log (multiline error)
        LogRecordData errorLog = EXPORTED_LOGS.get(0);
        assertEquals("ERROR", errorLog.getSeverityText());
        assertTrue(
                errorLog.getBody().asString().contains("java.lang.RuntimeException"),
                "Body should contain exception");
        assertTrue(
                errorLog.getBody()
                        .asString()
                        .contains("Caused by: java.net.SocketTimeoutException"),
                "Body should contain cause");
    }

    @Test
    void testAuditLog() throws Exception {
        File sampleFile = new ClassPathResource("audit.log").getFile();

        // Pattern for: [TIMESTAMP] [LEVEL] MESSAGE
        // Note the escaped brackets \[ \]
        String grokPattern =
                "\\[%{TIMESTAMP_ISO8601:timestamp}\\] \\[%{WORD:level}\\] %{GREEDYDATA:message}";

        // Regex for start of line: [YYYY-MM-DD
        String filePattern = "^\\[\\d{4}-\\d{2}-\\d{2}";

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", sampleFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", grokPattern)
                        .addLong(
                                "timestamp",
                                System.currentTimeMillis() + 1) // Ensure unique execution
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        assertEquals(3, EXPORTED_LOGS.size(), "Should have processed 3 audit log entries");

        LogRecordData firstLog = EXPORTED_LOGS.get(0);
        assertEquals("AUDIT", firstLog.getSeverityText());
        assertTrue(
                firstLog.getBody().asString().contains("User 'admin' logged in"),
                "Body should match message");
    }
}
