package com.iseries.otel.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.integration.config.EnableIntegration;

@SpringBootApplication
@EnableIntegration
public class OtelBridgeApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(OtelBridgeApplication.class, args)));
    }
}
