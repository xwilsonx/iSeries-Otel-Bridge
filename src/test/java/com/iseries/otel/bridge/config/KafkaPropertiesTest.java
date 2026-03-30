package com.iseries.otel.bridge.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@EnableConfigurationProperties(KafkaProperties.class)
@TestPropertySource(
        properties = {
            "kafka.bootstrap-servers=localhost:9092",
            "kafka.topic=test-topic",
            "kafka.consumer.group-id=test-group",
            "spring.main.allow-bean-definition-overriding=true",
            "spring.batch.job.enabled=false"
        })
class KafkaPropertiesTest {

    @Autowired private KafkaProperties kafkaProperties;

    @Test
    void testDefaultBootstrapServers() {
        KafkaProperties props = new KafkaProperties();
        assertEquals("localhost:9092", props.getBootstrapServers());
    }

    @Test
    void testDefaultTopic() {
        KafkaProperties props = new KafkaProperties();
        assertEquals("iseries-logs", props.getTopic());
    }

    @Test
    void testDefaultConsumerGroupId() {
        KafkaProperties props = new KafkaProperties();
        assertEquals("iseries-log-consumers", props.getConsumer().getGroupId());
    }

    @Test
    void testBootstrapServersBinding() {
        assertEquals("localhost:9092", kafkaProperties.getBootstrapServers());
    }

    @Test
    void testTopicBinding() {
        assertEquals("test-topic", kafkaProperties.getTopic());
    }

    @Test
    void testConsumerGroupIdBinding() {
        assertEquals("test-group", kafkaProperties.getConsumer().getGroupId());
    }

    @Test
    void testSetBootstrapServers() {
        KafkaProperties props = new KafkaProperties();
        props.setBootstrapServers("broker1:9092,broker2:9092");
        assertEquals("broker1:9092,broker2:9092", props.getBootstrapServers());
    }

    @Test
    void testSetTopic() {
        KafkaProperties props = new KafkaProperties();
        props.setTopic("custom-topic");
        assertEquals("custom-topic", props.getTopic());
    }

    @Test
    void testSetConsumerGroupId() {
        KafkaProperties props = new KafkaProperties();
        props.getConsumer().setGroupId("new-group");
        assertEquals("new-group", props.getConsumer().getGroupId());
    }

    @Test
    void testKafkaPropertiesNotNull() {
        assertNotNull(kafkaProperties);
    }
}
