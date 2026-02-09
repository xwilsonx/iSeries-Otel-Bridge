package com.iseries.otel.bridge;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@TestPropertySource(properties = {
        "otel.exporter.otlp.endpoint=http://localhost:4317",
        "spring.main.allow-bean-definition-overriding=true"
})
public class OtelConnectivityTest {

    @Autowired
    private SdkLoggerProvider sdkLoggerProvider;

    @Test
    void testSendLogToCollector() throws InterruptedException {
        Logger logger = sdkLoggerProvider.get("otel-connectivity-test");

        System.out.println("Sending test log to OTel Collector...");

        logger.logRecordBuilder()
                .setTimestamp(Instant.now())
                .setSeverityText("INFO")
                .setBody("Hello OpenTelemetry! This is a test log from iSeries-Otel-Bridge.")
                .setAttribute(AttributeKey.stringKey("test.run.id"),
                        "manual-verification-" + System.currentTimeMillis())
                .emit();

        // Flush to ensure it's sent
        sdkLoggerProvider.forceFlush().join(5, TimeUnit.SECONDS);

        System.out.println("Test log sent. Check Docker logs for output.");
    }
}
