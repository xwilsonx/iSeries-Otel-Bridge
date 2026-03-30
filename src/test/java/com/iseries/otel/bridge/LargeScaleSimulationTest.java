package com.iseries.otel.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iseries.otel.bridge.utils.LogGenerator;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class LargeScaleSimulationTest {

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
    void testOneHourSimulation() throws Exception {
        // Generate 1 hour of logs (60 minutes), 10 logs per second
        // Total logs = 60 * 60 * 10 = 36,000 logs
        File simulationFile = new File("target/test-classes/simulation_1h.log");
        LogGenerator.generate(simulationFile.getAbsolutePath(), 60, 10);

        System.out.println(
                "Generated simulation file size: " + simulationFile.length() / 1024 + " KB");

        // Pattern handles standard logs, errors (multiline via greedy), and trace IDs
        String grokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:level}.*\\[trace_id=%{DATA:trace_id} span_id=%{DATA:span_id}\\]? %{GREEDYDATA:message}";
        // Also handle metric lines which don't have brackets
        // A complex pattern or OR logic in Grok is best, but for this test we use a pattern that
        // matches the common prefix
        // Let's refine the pattern to be more flexible:
        // TIMESTAMP LEVEL ...rest
        // But we want to extract trace_id if present.

        // Revised Pattern:
        // TIMESTAMP LEVEL (optional [trace...]) MESSAGE
        String effectiveGrokPattern =
                "(?s)%{TIMESTAMP_ISO8601:timestamp} %{WORD:level}\\s*(?:\\[trace_id=%{DATA:trace_id} span_id=%{DATA:span_id}\\])?\\s*%{GREEDYDATA:message}";

        String filePattern = "^\\d{4}-\\d{2}-\\d{2}";

        long startTime = System.currentTimeMillis();

        JobParameters params =
                new JobParametersBuilder()
                        .addString("input.file.path", simulationFile.getAbsolutePath())
                        .addString("input.file.pattern", filePattern)
                        .addString("parser.grok.pattern", effectiveGrokPattern)
                        .addLong("timestamp", System.currentTimeMillis())
                        .toJobParameters();

        JobExecution execution = jobLauncher.run(logConversionJob, params);

        long endTime = System.currentTimeMillis();

        assertEquals(BatchStatus.COMPLETED, execution.getStatus());
        sdkLoggerProvider.forceFlush().join(10, TimeUnit.SECONDS);

        System.out.println("Processing Time: " + (endTime - startTime) + " ms");
        System.out.println("Total Logs Exported: " + EXPORTED_LOGS.size());

        // Assertions
        // We generated 36,000 entries.
        // 4% are ERRORs which are multiline (3 lines).
        // MultilineReader combines them into 1 record.
        // So we expect exactly 36,000 *records*.
        assertEquals(
                36000,
                EXPORTED_LOGS.size(),
                "Should have processed exactly 36,000 logical records");

        // Verify Trace IDs are populated for a random sample
        List<LogRecordData> tracedLogs =
                EXPORTED_LOGS.stream()
                        .filter(l -> l.getSpanContext().isValid())
                        .collect(Collectors.toList());

        // We expect about 99% to have traces (INFO, DEBUG, WARN, ERROR). METRIC (1%) might not if
        // logic excludes it?
        // Generator puts traces on INFO, DEBUG, WARN, ERROR. METRIC does not have [trace_id=...] in
        // the generator above.
        // So ~99% should have traces.
        assertTrue(tracedLogs.size() > 35000, "Most logs should have trace context");

        // Verify Metrics
        long metricCount =
                EXPORTED_LOGS.stream()
                        .filter(l -> l.getBody().asString().contains("cpu_usage"))
                        .count();
        assertTrue(metricCount > 0, "Should have some metric logs");
    }
}
