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
class AdvancedFeaturesTest {

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
    void testTraceContextInjection() throws Exception {
        File sampleFile = new ClassPathResource("trace_app.log").getFile();

        // Pattern: DATE TIME LEVEL [trace_id=... span_id=...] Message
        String grokPattern =
                "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} \\s*\\[trace_id=%{DATA:trace_id} span_id=%{DATA:span_id}\\] %{GREEDYDATA:message}";
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

        assertEquals(3, EXPORTED_LOGS.size());

        LogRecordData log = EXPORTED_LOGS.get(0);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", log.getSpanContext().getTraceId());
        assertEquals("00f067aa0ba902b7", log.getSpanContext().getSpanId());
        assertTrue(log.getBody().asString().contains("Request processing started"));
    }

    @Test
    void testMetricsAsLogs() throws Exception {
        File sampleFile = new ClassPathResource("metrics_app.log").getFile();

        // Pattern: DATE TIME METRIC key=value key=value...
        // We capture the "values" part as message, but we could also parse them individually if
        // Grok supported it natively
        // Here we show how to capture them as attributes if we parse them carefully.
        // For this example, we treat them as log lines with specific attributes.
        String grokPattern =
                "%{TIMESTAMP_ISO8601:timestamp} %{WORD:level} cpu_usage=%{INT:cpu_usage} memory_usage=%{INT:memory_usage}";
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

        assertEquals(3, EXPORTED_LOGS.size());

        LogRecordData metricLog = EXPORTED_LOGS.get(0);
        assertEquals("METRIC", metricLog.getSeverityText());
        // Verify that cpu_usage was extracted as an attribute
        assertEquals(
                "45",
                metricLog
                        .getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("cpu_usage"))
                        .toString());
        assertEquals(
                "2048",
                metricLog
                        .getAttributes()
                        .asMap()
                        .get(io.opentelemetry.api.common.AttributeKey.stringKey("memory_usage"))
                        .toString());
    }
}
