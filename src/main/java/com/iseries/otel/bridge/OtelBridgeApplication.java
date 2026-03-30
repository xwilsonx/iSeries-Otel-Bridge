package com.iseries.otel.bridge;

import com.iseries.otel.bridge.config.KafkaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.integration.config.EnableIntegration;

@SpringBootApplication
@EnableIntegration
@EnableConfigurationProperties(KafkaProperties.class)
public class OtelBridgeApplication {

    public static void main(String[] args) {
        System.exit(
                SpringApplication.exit(SpringApplication.run(OtelBridgeApplication.class, args)));
    }
}
