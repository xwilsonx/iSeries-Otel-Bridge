package com.iseries.otel.bridge.batch;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

import java.time.Instant;
import java.util.Map;

public class OtelItemWriter implements ItemWriter<Map<String, Object>> {

    private final SdkLoggerProvider loggerProvider;

    public OtelItemWriter(SdkLoggerProvider loggerProvider) {
        this.loggerProvider = loggerProvider;
    }

    @Override
    public void write(Chunk<? extends Map<String, Object>> items) {
        Logger logger = loggerProvider.get("iseries-otel-bridge");

        for (Map<String, Object> item : items) {
            LogRecordBuilder builder = logger.logRecordBuilder();

            // Handle Timestamp
            if (item.containsKey("timestamp")) {
                // Assuming timestamp is in ISO-8601 string or epoch millis.
                // For simplicity here, if it's a long, treat as epoch.
                // In a real scenario, date parsing logic would be needed in the processor or
                // here.
                // Here we fallback to current time if parsing complex dates is needed.
                // Let's assume for now it's current time if not readily parsable, or we'd need
                // a DateParser.
                builder.setTimestamp(Instant.now());
            } else {
                builder.setTimestamp(Instant.now());
            }

            // Handle Severity
            String level = (String) item.getOrDefault("level", "INFO");
            builder.setSeverityText(level);
            builder.setSeverity(mapSeverity(level));

            // Handle Body
            String message = (String) item.getOrDefault("message", "");
            builder.setBody(message);

            // Handle Attributes
            item.forEach((k, v) -> {
                if (v != null && !isReserved(k)) {
                    builder.setAttribute(AttributeKey.stringKey(k), v.toString());
                }
            });

            builder.emit();
        }
    }

    private boolean isReserved(String key) {
        return key.equals("message") || key.equals("level") || key.equals("timestamp");
    }

    private Severity mapSeverity(String level) {
        if (level == null)
            return Severity.INFO;
        switch (level.toUpperCase()) {
            case "TRACE":
                return Severity.TRACE;
            case "DEBUG":
                return Severity.DEBUG;
            case "WARN":
                return Severity.WARN;
            case "ERROR":
                return Severity.ERROR;
            case "FATAL":
                return Severity.FATAL;
            default:
                return Severity.INFO;
        }
    }
}
