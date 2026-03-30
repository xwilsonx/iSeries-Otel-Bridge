package com.iseries.otel.bridge.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.UUID;

public class LogGenerator {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Random RANDOM = new Random();

    public static File generate(String fileName, int durationMinutes, int logsPerSecond)
            throws IOException {
        File file = new File(fileName);
        // Ensure parent directories exist
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            LocalDateTime currentTime = LocalDateTime.of(2023, 10, 27, 10, 0, 0);
            LocalDateTime endTime = currentTime.plusMinutes(durationMinutes);

            while (currentTime.isBefore(endTime)) {
                for (int i = 0; i < logsPerSecond; i++) {
                    writer.write(generateRandomLogLine(currentTime));
                    writer.newLine();
                }
                currentTime = currentTime.plusSeconds(1);
            }
        }
        return file;
    }

    private static String generateRandomLogLine(LocalDateTime timestamp) {
        int rand = RANDOM.nextInt(100);
        String tsString = timestamp.format(FORMATTER);
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String spanId = traceId.substring(0, 16);

        if (rand < 70) {
            // INFO - Standard Request (70%)
            return String.format(
                    "%s INFO  [trace_id=%s span_id=%s] Request processed successfully for user_%d",
                    tsString, traceId, spanId, RANDOM.nextInt(1000));
        } else if (rand < 90) {
            // DEBUG - Verbose (20%)
            return String.format(
                    "%s DEBUG [trace_id=%s span_id=%s] Database query took %d ms",
                    tsString, traceId, spanId, RANDOM.nextInt(50));
        } else if (rand < 95) {
            // WARN - Slow Query (5%)
            return String.format(
                    "%s WARN  [trace_id=%s span_id=%s] Slow query detected on table customers",
                    tsString, traceId, spanId);
        } else if (rand < 99) {
            // ERROR - Exception (4%)
            return String.format(
                    "%s ERROR [trace_id=%s span_id=%s] Internal Server Error\n"
                            + "java.lang.NullPointerException: value cannot be null\n"
                            + "\tat com.iseries.service.UserService.process(UserService.java:%d)\n"
                            + "\tat com.iseries.controller.UserController.handle(UserController.java:45)",
                    tsString, traceId, spanId, RANDOM.nextInt(100) + 10);
        } else {
            // METRIC (1%)
            return String.format(
                    "%s METRIC cpu_usage=%d memory_usage=%d disk_io=%d",
                    tsString,
                    RANDOM.nextInt(100),
                    RANDOM.nextInt(4096) + 1024,
                    RANDOM.nextInt(500));
        }
    }
}
