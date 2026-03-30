package com.iseries.otel.bridge.batch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GrokItemProcessorTest {

    @Test
    void testGrokParsing() {
        // Pattern for a simple log: TIMESTAMP LEVEL MESSAGE
        String pattern = "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level} %{GREEDYDATA:message}";
        GrokItemProcessor processor = new GrokItemProcessor(pattern);

        String logLine = "2023-10-27 10:00:00 INFO  Application started successfully";
        Map<String, Object> result = processor.process(logLine);

        assertNotNull(result);
        assertEquals("2023-10-27 10:00:00", result.get("timestamp"));
        assertEquals("INFO", result.get("level"));
        assertEquals(" Application started successfully", result.get("message"));
    }

    @Test
    void testGrokParsingFailure() {
        String pattern = "%{TIMESTAMP_ISO8601:timestamp} %{LOGLEVEL:level}";
        GrokItemProcessor processor = new GrokItemProcessor(pattern);

        String badLine = "This is not a log line";
        Map<String, Object> result = processor.process(badLine);

        assertNotNull(result);
        assertEquals(true, result.get("parse_error"));
        assertEquals(badLine, result.get("message"));
    }
}
