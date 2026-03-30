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
 * Integration tests for iSeries-specific log formats. Covers QHST (history log), job log, and
 * message queue log formats.
 */
@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class ISeriesLogFormatsTest {

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
    void testISeriesQhstLog() throws Exception {
        File sampleFile = new ClassPathResource("iseries_qhst.log").getFile();

        // Pattern: DATE TIME MSGID MSG_TYPE MSG_NUMBER MESSAGE_TYPE MESSAGE
        // Captures the iSeries message ID (e.g., CPI1125, CPF9801) as a separate attribute
        String grokPattern =
                "(?s)%{DATA:date} %{DATA:time}  QHST  %{DATA:msg_num}  %{WORD:msgid}  %{WORD:msg_type} %{GREEDYDATA:message}";
        String filePattern = "^\\d{2}/\\d{2}/\\d{4}";

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

        // Verify we processed entries
        assertTrue(
                EXPORTED_LOGS.size() >= 4,
                "Should have processed at least 4 QHST log entries, got: " + EXPORTED_LOGS.size());

        // Verify first log has message ID as attribute
        LogRecordData firstLog = EXPORTED_LOGS.get(0);
        assertTrue(
                firstLog.getAttributes().asMap().values().stream()
                        .anyMatch(
                                v -> v.toString().contains("CPF") || v.toString().contains("CPI")),
                "First log should have an AS/400 message ID");
    }

    @Test
    void testISeriesJobLog() throws Exception {
        File sampleFile = new ClassPathResource("iseries_joblog.log").getFile();

        // Job log has format: DATE TIME JOBLOG JOB_NAME MSGID TYPE SEV PROGRAM ...
        String grokPattern =
                "(?s)%{DATA:date} %{DATA:time}  JOBLOG  %{DATA:job_name}  %{WORD:msgid}  %{WORD:msgtype}  %{DATA:pgm}  %{WORD:severity}  %{GREEDYDATA:message}";
        String filePattern = "^\\d{2}/\\d{2}/\\d{4}";

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

        // At minimum, we should have parsed some records
        assertTrue(
                EXPORTED_LOGS.size() >= 4,
                "Should have processed at least 4 job log entries, got: " + EXPORTED_LOGS.size());
    }

    @Test
    void testISeriesMessageQueueLog() throws Exception {
        File sampleFile = new ClassPathResource("iseries_msgq.log").getFile();

        // Pattern: DATE TIME MSGQ JOB_NAME MSGID MESSAGE_TYPE MESSAGE
        String grokPattern =
                "(?s)%{DATA:date} %{DATA:time}  MSGQ  %{DATA:job_name}  %{WORD:msgid}  %{WORD:msg_type} %{GREEDYDATA:message}";
        String filePattern = "^\\d{2}/\\d{2}/\\d{4}";

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

        // Verify we processed entries
        assertTrue(
                EXPORTED_LOGS.size() >= 4,
                "Should have processed at least 4 message queue entries, got: "
                        + EXPORTED_LOGS.size());

        // Verify message ID extraction
        boolean hasMsgId =
                EXPORTED_LOGS.stream()
                        .anyMatch(
                                log ->
                                        log.getAttributes().asMap().values().stream()
                                                .anyMatch(
                                                        v ->
                                                                v.toString().contains("CPF")
                                                                        || v.toString()
                                                                                .contains("CPI")));
        assertTrue(hasMsgId, "Should have extracted AS/400 message IDs");
    }
}
