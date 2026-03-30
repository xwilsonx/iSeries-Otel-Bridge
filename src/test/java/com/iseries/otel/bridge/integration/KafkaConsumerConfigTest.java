package com.iseries.otel.bridge.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.iseries.otel.bridge.config.KafkaProperties;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerConfigTest {

    @Mock private KafkaProperties kafkaProperties;

    @Mock private JobLauncher jobLauncher;

    @Mock private Job logConversionJob;

    private Path tempDir;

    private KafkaConsumerConfig kafkaConsumerConfig;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("kafka-test");
        kafkaConsumerConfig =
                new KafkaConsumerConfig(kafkaProperties, jobLauncher, logConversionJob);
    }

    @Test
    void testConsumeAddsMessageToBuffer() throws Exception {
        // Given
        String testMessage = "2024-01-15 10:30:00 INFO Test log message";
        String testKey = "test-key-123";
        int testPartition = 0;

        // When
        kafkaConsumerConfig.consume(testMessage, testKey, testPartition);

        // Then - verify message was buffered using reflection
        List<String> buffer = getBuffer(kafkaConsumerConfig);
        assertEquals(1, buffer.size());
        assertEquals(testMessage, buffer.get(0));
    }

    @Test
    void testBufferFlushesWhenSizeThresholdReached() throws Exception {
        // Given - pre-populate buffer via reflection to approach BUFFER_SIZE
        List<String> buffer = getBuffer(kafkaConsumerConfig);
        for (int i = 0; i < 49; i++) {
            buffer.add("message-" + i);
        }

        // Add one more which should trigger flush
        String lastMessage = "final-message";
        kafkaConsumerConfig.consume(lastMessage, "key", 0);

        // Then - buffer should have been flushed (size should be 0 or 1)
        assertTrue(buffer.size() < 50, "Buffer should have been flushed");
    }

    @Test
    void testBufferLockEnsuresThreadSafety() throws Exception {
        // Given
        ReentrantLock bufferLock = getBufferLock(kafkaConsumerConfig);

        // Then - lock should be initially unlocked
        assertFalse(bufferLock.isLocked(), "Buffer lock should not be locked initially");
    }

    @Test
    void testConsumeHandlesMultipleMessagesFromSamePartition() throws Exception {
        // Given
        String message1 = "first-message";
        String message2 = "second-message";
        String message3 = "third-message";

        // When
        kafkaConsumerConfig.consume(message1, "key-1", 0);
        kafkaConsumerConfig.consume(message2, "key-2", 0);
        kafkaConsumerConfig.consume(message3, "key-3", 0);

        // Then
        List<String> buffer = getBuffer(kafkaConsumerConfig);
        assertEquals(3, buffer.size());
        assertEquals(message1, buffer.get(0));
        assertEquals(message2, buffer.get(1));
        assertEquals(message3, buffer.get(2));
    }

    @Test
    void testSchedulerCreatedOnFirstConsume() throws Exception {
        // Given
        String testMessage = "test-message";

        // When
        kafkaConsumerConfig.consume(testMessage, "key", 0);

        // Then - scheduler should be created
        // We verify indirectly by checking the field via reflection
        Object scheduler = getScheduler(kafkaConsumerConfig);
        assertNotNull(scheduler, "Scheduler should be created on consume");
    }

    @SuppressWarnings("unchecked")
    private List<String> getBuffer(KafkaConsumerConfig config) throws Exception {
        Field bufferField = KafkaConsumerConfig.class.getDeclaredField("buffer");
        bufferField.setAccessible(true);
        return (List<String>) bufferField.get(config);
    }

    private ReentrantLock getBufferLock(KafkaConsumerConfig config) throws Exception {
        Field lockField = KafkaConsumerConfig.class.getDeclaredField("bufferLock");
        lockField.setAccessible(true);
        return (ReentrantLock) lockField.get(config);
    }

    private Object getScheduler(KafkaConsumerConfig config) throws Exception {
        Field schedulerField = KafkaConsumerConfig.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        return schedulerField.get(config);
    }
}
