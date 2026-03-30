package com.iseries.otel.bridge.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.Chunk;

class OtelItemWriterTest {

    @Test
    void testWrite() {
        // Mocks
        SdkLoggerProvider mockProvider = mock(SdkLoggerProvider.class);
        Logger mockLogger = mock(Logger.class);
        LogRecordBuilder mockBuilder = mock(LogRecordBuilder.class);

        when(mockProvider.get(anyString())).thenReturn(mockLogger);
        when(mockLogger.logRecordBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.setTimestamp(any(Instant.class))).thenReturn(mockBuilder);
        when(mockBuilder.setSeverity(any(Severity.class))).thenReturn(mockBuilder);
        when(mockBuilder.setSeverityText(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.setBody(anyString())).thenReturn(mockBuilder);
        when(mockBuilder.setAttribute(any(AttributeKey.class), any())).thenReturn(mockBuilder);

        OtelItemWriter writer = new OtelItemWriter(mockProvider);

        // Data
        Map<String, Object> item =
                Map.of(
                        "timestamp", System.currentTimeMillis(),
                        "level", "ERROR",
                        "message", "Something went wrong",
                        "custom_field", "value");
        Chunk<Map<String, Object>> chunk = new Chunk<>(List.of(item));

        // Execute
        writer.write(chunk);

        // Verify
        verify(mockLogger).logRecordBuilder();
        verify(mockBuilder).setSeverity(Severity.ERROR);
        verify(mockBuilder).setBody("Something went wrong");
        verify(mockBuilder).setAttribute(AttributeKey.stringKey("custom_field"), "value");
        verify(mockBuilder).emit();
    }
}
