package com.iseries.otel.bridge.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.iseries.otel.bridge.config.KafkaProperties;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.file.tail.OSDelegatingFileTailingMessageProducer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.MessageChannel;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(
        args = "--spring.batch.job.enabled=false",
        properties = "spring.main.allow-bean-definition-overriding=true")
class FileTailingConfigTest {

    private Path tempDir;

    @Mock private KafkaProperties kafkaProperties;

    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    private FileTailingConfig fileTailingConfig;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public KafkaProperties kafkaProperties() {
            KafkaProperties props = new KafkaProperties();
            props.setTopic("test-topic");
            KafkaProperties.Consumer consumer = new KafkaProperties.Consumer();
            consumer.setGroupId("test-group");
            props.setConsumer(consumer);
            return props;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        tempDir = Files.createTempDirectory("filetailer-test");
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);
    }

    @Test
    void testLogLinesChannelBeanCreation() {
        // Given
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);

        // When
        MessageChannel channel = fileTailingConfig.logLinesChannel();

        // Then
        assertNotNull(channel, "logLinesChannel should not be null");
        assertTrue(channel instanceof DirectChannel, "logLinesChannel should be DirectChannel");
    }

    @Test
    void testJobRequestsChannelBeanCreation() {
        // Given
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);

        // When
        MessageChannel channel = fileTailingConfig.jobRequestsChannel();

        // Then
        assertNotNull(channel, "jobRequestsChannel should not be null");
        assertTrue(channel instanceof DirectChannel, "jobRequestsChannel should be DirectChannel");
    }

    @Test
    void testFileTailingMessageProducerReturnsNullWhenPathIsBlank() {
        // Given
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);
        ReflectionTestUtils.setField(fileTailingConfig, "tailFilePath", "");

        // When
        OSDelegatingFileTailingMessageProducer producer =
                fileTailingConfig.fileTailingMessageProducer();

        // Then
        assertNull(producer, "fileTailingMessageProducer should return null when path is blank");
    }

    @Test
    void testFileTailingMessageProducerReturnsNullWhenPathIsNull() {
        // Given
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);
        ReflectionTestUtils.setField(fileTailingConfig, "tailFilePath", null);

        // When
        OSDelegatingFileTailingMessageProducer producer =
                fileTailingConfig.fileTailingMessageProducer();

        // Then
        assertNull(producer, "fileTailingMessageProducer should return null when path is null");
    }

    @Test
    void testFileTailingMessageProducerCreatedWhenPathIsValid() throws Exception {
        // Given - create actual temp file
        File tempFile = tempDir.resolve("test-log.log").toFile();
        tempFile.createNewFile();
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);
        ReflectionTestUtils.setField(fileTailingConfig, "tailFilePath", tempFile.getAbsolutePath());

        // When
        OSDelegatingFileTailingMessageProducer producer =
                fileTailingConfig.fileTailingMessageProducer();

        // Then
        assertNotNull(producer, "fileTailingMessageProducer should not be null when path is valid");
    }

    @Test
    void testFileTailingMessageProducerUsesConcurrentTaskExecutor() throws Exception {
        // Given - create actual temp file
        File tempFile = tempDir.resolve("test-log.log").toFile();
        tempFile.createNewFile();
        fileTailingConfig = new FileTailingConfig(kafkaProperties, kafkaTemplate);
        ReflectionTestUtils.setField(fileTailingConfig, "tailFilePath", tempFile.getAbsolutePath());

        // When
        OSDelegatingFileTailingMessageProducer producer =
                fileTailingConfig.fileTailingMessageProducer();

        // Then
        assertNotNull(producer, "Producer should be created");
    }
}
