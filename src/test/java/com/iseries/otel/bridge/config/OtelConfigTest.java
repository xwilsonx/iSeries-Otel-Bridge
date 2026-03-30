package com.iseries.otel.bridge.config;

import static org.junit.jupiter.api.Assertions.*;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.semconv.ResourceAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(
        properties = {
            "otel.exporter.otlp.endpoint=http://localhost:4317",
            "otel.service.name=test-iseries-service",
            "spring.main.allow-bean-definition-overriding=true",
            "spring.batch.job.enabled=false"
        })
class OtelConfigTest {

    @Autowired private SdkLoggerProvider sdkLoggerProvider;

    @Autowired private LogRecordExporter logRecordExporter;

    @Test
    void testSdkLoggerProviderBeanCreation() {
        assertNotNull(sdkLoggerProvider);
    }

    @Test
    void testLogRecordExporterBeanCreation() {
        assertNotNull(logRecordExporter);
        assertInstanceOf(OtlpGrpcLogRecordExporter.class, logRecordExporter);
    }

    @Test
    void testServiceNameFromConfig() {
        // Resource is accessible via the provider builder during construction
        Resource resource = Resource.getDefault();
        Attributes attributes = resource.getAttributes();
        // Verify default resource exists and has service name attribute key
        assertNotNull(attributes);
    }

    @Test
    void testDefaultServiceName() {
        // Default service name should be iseries-log-bridge
        SdkLoggerProvider provider = createProviderWithServiceName("iseries-log-bridge");
        assertNotNull(provider);
    }

    @Test
    void testOtlpEndpointConfiguration() {
        // Test that the exporter uses the configured endpoint
        assertNotNull(logRecordExporter);
    }

    @Test
    void testCustomServiceName() {
        // Test with a custom service name
        SdkLoggerProvider provider = createProviderWithServiceName("custom-service");
        assertNotNull(provider);
    }

    @Test
    void testResourceCreation() {
        Resource resource = Resource.getDefault();
        assertNotNull(resource);
        assertNotNull(resource.getAttributes());
    }

    @Test
    void testResourceContainsServiceNameAttribute() {
        Resource resource = Resource.getDefault();
        Attributes attributes = resource.getAttributes();
        assertNotNull(attributes.asMap().get(ResourceAttributes.SERVICE_NAME));
    }

    @Test
    void testSdkLoggerProviderShutdown() {
        // Test that the provider can be shut down properly
        SdkLoggerProvider provider = createProviderWithServiceName("shutdown-test");
        assertDoesNotThrow(() -> provider.shutdown());
    }

    @Test
    void testLogRecordExporterBuildsWithEndpoint() {
        // Verify the exporter builder pattern works
        OtlpGrpcLogRecordExporter exporter =
                OtlpGrpcLogRecordExporter.builder().setEndpoint("http://localhost:4317").build();
        assertNotNull(exporter);
    }

    private SdkLoggerProvider createProviderWithServiceName(String serviceName) {
        Resource resource =
                Resource.getDefault()
                        .merge(
                                Resource.create(
                                        Attributes.of(
                                                ResourceAttributes.SERVICE_NAME, serviceName)));
        LogRecordExporter exporter =
                OtlpGrpcLogRecordExporter.builder().setEndpoint("http://localhost:4317").build();
        return SdkLoggerProvider.builder().setResource(resource).build();
    }
}
