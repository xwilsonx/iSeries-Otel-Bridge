package com.iseries.otel.bridge.integration;

import com.iseries.otel.bridge.config.KafkaProperties;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private static final int BUFFER_SIZE = 50;
    private static final long BUFFER_TIMEOUT_MS = 1000;

    private final KafkaProperties kafkaProperties;
    private final JobLauncher jobLauncher;
    private final Job logConversionJob;

    private final List<String> buffer = new ArrayList<>();
    private final ReentrantLock bufferLock = new ReentrantLock();
    private ScheduledExecutorService scheduler;

    @KafkaListener(
            topics = "#{@kafkaProperties.topic}",
            groupId = "#{@kafkaProperties.consumer.groupId}")
    public void consume(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
        log.debug("Received message from partition {} with key {}: {}", partition, key, message);

        bufferLock.lock();
        try {
            buffer.add(message);
            if (buffer.size() >= BUFFER_SIZE) {
                flushBuffer();
            }
        } finally {
            bufferLock.unlock();
        }

        // Schedule timeout-based flush if not already scheduled
        scheduleFlushIfNeeded();
    }

    private void scheduleFlushIfNeeded() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.schedule(
                    () -> {
                        bufferLock.lock();
                        try {
                            if (!buffer.isEmpty()) {
                                flushBuffer();
                            }
                        } finally {
                            bufferLock.unlock();
                        }
                    },
                    BUFFER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void flushBuffer() {
        if (buffer.isEmpty()) {
            return;
        }

        List<String> linesToProcess = new ArrayList<>(buffer);
        buffer.clear();

        try {
            // Create temp file from buffered lines
            File tempFile = Files.createTempFile("kafka-log-chunk-", ".txt").toFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                for (String line : linesToProcess) {
                    writer.write(line);
                    writer.newLine();
                }
            }

            log.info(
                    "Flushing {} log lines to temp file: {}",
                    linesToProcess.size(),
                    tempFile.getAbsolutePath());

            // Launch batch job
            launchJob(tempFile);

        } catch (IOException e) {
            log.error("Error creating temp file for log chunk", e);
        }
    }

    private void launchJob(File tempFile) {
        try {
            JobParameters jobParameters =
                    new JobParametersBuilder()
                            .addString("input.file.path", tempFile.getAbsolutePath())
                            .addString("input.file.pattern", "^.*")
                            .addString("parser.grok.pattern", "%{GREEDYDATA:message}")
                            .addString("uuid", UUID.randomUUID().toString())
                            .toJobParameters();

            log.info("Launching log conversion job for file: {}", tempFile.getAbsolutePath());
            jobLauncher.run(logConversionJob, jobParameters);

        } catch (Exception e) {
            log.error("Error launching batch job", e);
        }
    }
}
