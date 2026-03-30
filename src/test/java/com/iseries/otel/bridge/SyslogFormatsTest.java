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
 * Integration tests for RFC 5424 syslog format parsing. Validates that structured syslog messages
 * are correctly parsed and exported as OpenTelemetry LogRecords.
 */
@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class SyslogFormatsTest {

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
    void testSyslogRfc5424Parsing() throws Exception {
        File sampleFile = new ClassPathResource("syslog_rfc5424.log").getFile();

        // RFC 5424 format: <PRI>VERSION TIMESTAMP HOSTNAME APP-NAME PROCID MSGID [SD] MSG
        // We extract the key fields using a Grok pattern
        String grokPattern =
                "<%{INT:priority}>%{INT:version} %{TIMESTAMP_ISO8601:timestamp} %{DATA:hostname} %{DATA:appname} %{DATA:procid} %{DATA:msgid} %{GREEDYDATA:message}";
        // Each syslog line starts with <
        String filePattern = "^<\\d+>";

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

        assertEquals(6, EXPORTED_LOGS.size(), "Should have processed 6 syslog entries");

        // Verify first log has hostname as attribute
        LogRecordData firstLog = EXPORTED_LOGS.get(0);
        assertEquals(
                "myhost.example.com",
                firstLog.getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("hostname"))
                        .toString());
        assertEquals(
                "appname",
                firstLog.getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("appname"))
                        .toString());
        assertTrue(
                firstLog.getBody().asString().contains("User login successful"),
                "Body should contain message text");

        // Verify FATAL syslog (postgres)
        LogRecordData fatalLog = EXPORTED_LOGS.get(5);
        assertEquals(
                "db.example.com",
                fatalLog.getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("hostname"))
                        .toString());
        assertTrue(
                fatalLog.getBody().asString().contains("too many connections"),
                "Body should contain FATAL postgres message");
    }

    @Test
    void testSyslogPriorityExtraction() throws Exception {
        File sampleFile = new ClassPathResource("syslog_rfc5424.log").getFile();

        String grokPattern =
                "<%{INT:priority}>%{INT:version} %{TIMESTAMP_ISO8601:timestamp} %{DATA:hostname} %{DATA:appname} %{DATA:procid} %{DATA:msgid} %{GREEDYDATA:message}";
        String filePattern = "^<\\d+>";

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

        // Verify priority values are extracted as attributes
        // Priority 165 = facility 20 (local4) * 8 + severity 5 (notice)
        LogRecordData firstLog = EXPORTED_LOGS.get(0);
        assertEquals(
                "165",
                firstLog.getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("priority"))
                        .toString());

        // Priority 131 = facility 16 (local0) * 8 + severity 3 (error)
        LogRecordData sshLog = EXPORTED_LOGS.get(3);
        assertEquals(
                "131",
                sshLog.getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("priority"))
                        .toString());
        assertTrue(
                sshLog.getBody().asString().contains("Failed password for root"),
                "SSH failure message should be in body");
    }
}
