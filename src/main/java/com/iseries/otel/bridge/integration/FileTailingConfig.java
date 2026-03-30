package com.iseries.otel.bridge.integration;

import com.iseries.otel.bridge.config.KafkaProperties;
import java.io.File;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.file.tail.OSDelegatingFileTailingMessageProducer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class FileTailingConfig {

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;

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

        OSDelegatingFileTailingMessageProducer producer =
                new OSDelegatingFileTailingMessageProducer();
        producer.setFile(new File(tailFilePath));
        producer.setOutputChannel(logLinesChannel());

        // Use a dedicated task executor to avoid blocking main threads
        producer.setTaskExecutor(new ConcurrentTaskExecutor(Executors.newSingleThreadExecutor()));
        return producer;
    }

    /**
     * Integration Flow: 1. Receive lines from log file tailer 2. Publish each line to Kafka topic
     * (keyed by timestamp for ordering)
     *
     * <p>The Kafka consumer (KafkaConsumerConfig) will batch these lines and launch the batch job
     * for processing.
     */
    @Bean
    public IntegrationFlow tailingFlow() {
        return IntegrationFlow.from(logLinesChannel())
                .<String>handle(
                        String.class,
                        (payload, headers) -> {
                            String key = String.valueOf(System.currentTimeMillis());
                            kafkaTemplate.send(kafkaProperties.getTopic(), key, payload);
                            log.debug(
                                    "Published log line to Kafka topic {}: {}",
                                    kafkaProperties.getTopic(),
                                    payload);
                            return null;
                        })
                .get();
    }
}
