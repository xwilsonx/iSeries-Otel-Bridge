package com.iseries.otel.bridge.config;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ResourceAttributes;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtelConfig {

    @Value("${otel.service.name:iseries-log-bridge}")
    private String serviceName;

    @Value("${otel.exporter.otlp.endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Bean
    public LogRecordExporter logRecordExporter() {
        return OtlpGrpcLogRecordExporter.builder().setEndpoint(otlpEndpoint).build();
    }

    @Bean
    public SdkLoggerProvider sdkLoggerProvider(LogRecordExporter logRecordExporter) {
        Resource resource =
                Resource.getDefault()
                        .merge(
                                Resource.create(
                                        Attributes.of(
                                                ResourceAttributes.SERVICE_NAME, serviceName)));

        return SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(
                        BatchLogRecordProcessor.builder(logRecordExporter)
                                .setScheduleDelay(500, TimeUnit.MILLISECONDS)
                                .build())
                .build();
    }
}
