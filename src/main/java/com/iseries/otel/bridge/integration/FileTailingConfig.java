package com.iseries.otel.bridge.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.integration.launch.JobLaunchRequest;
import org.springframework.batch.integration.launch.JobLaunchingGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.file.tail.OSDelegatingFileTailingMessageProducer;
// import org.springframework.integration.file.support.FileTailingMessageProducerSupport; // Removed
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class FileTailingConfig {

    private final JobLauncher jobLauncher;
    private final Job logConversionJob;

    @Value("${input.file.path.tail:}")
    private String tailFilePath;

    @Bean
    public MessageChannel logLinesChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageChannel jobRequestsChannel() {
        return new DirectChannel();
    }

    @Bean
    public OSDelegatingFileTailingMessageProducer fileTailingMessageProducer() {
        if (tailFilePath == null || tailFilePath.isBlank()) {
            log.info("No tail file path configured. Tailing disabled.");
            return null;
        }

        OSDelegatingFileTailingMessageProducer producer = new OSDelegatingFileTailingMessageProducer();
        producer.setFile(new File(tailFilePath));
        producer.setOutputChannel(logLinesChannel());

        // Use a dedicated task executor to avoid blocking main threads
        producer.setTaskExecutor(new ConcurrentTaskExecutor(Executors.newSingleThreadExecutor()));
        return producer;
    }

    /**
     * Integration Flow:
     * 1. Aggregate lines (e.g., every 50 lines or 1 second)
     * 2. Write to temp file
     * 3. Launch Batch Job
     */
    @Bean
    public IntegrationFlow tailingFlow() {
        return IntegrationFlow.from(logLinesChannel())
                .aggregate(a -> a
                        .correlationStrategy(m -> 1) // Correlate all logs together
                        .releaseStrategy(g -> g.size() >= 50) // Release every 50 lines
                        .groupTimeout(1000) // Or every 1 second
                        .sendPartialResultOnExpiry(true))
                .handle(message -> {
                    List<String> lines = (List<String>) message.getPayload();
                    if (lines.isEmpty())
                        return;

                    try {
                        // 1. Write to Temp File
                        File tempFile = Files.createTempFile("log-chunk-", ".txt").toFile();
                        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                            for (String line : lines) {
                                writer.write(line);
                                writer.newLine();
                            }
                        }

                        // 2. Launch Job
                        // The temp file path is passed as Job Parameter.
                        // Note: A real system needs a listener to delete this file after generic job
                        // completion.
                        log.info("Launching job for chunk file: {}", tempFile.getAbsolutePath());

                        JobLaunchRequest request = new JobLaunchRequest(
                                logConversionJob,
                                new JobParametersBuilder()
                                        .addString("input.file.path", tempFile.getAbsolutePath())
                                        .addString("input.file.pattern", "^.*") // Treat everything as new record for
                                                                                // now
                                        .addString("parser.grok.pattern", "%{GREEDYDATA:message}") // Simple fallback
                                        .addString("uuid", UUID.randomUUID().toString()) // Unique execution
                                        .toJobParameters());

                        // We manually launch here or delegate to gateway.
                        // Using manual launch for simplicity in 'handle' block.
                        jobLauncher.run(request.getJob(), request.getJobParameters());

                    } catch (Exception e) {
                        log.error("Error processing log chunk", e);
                    }
                })
                .get();
    }
}
