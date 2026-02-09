package com.iseries.otel.bridge.batch;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultilineLogItemReaderTest {

    @Test
    void testMultilineReading() throws Exception {
        String logData = "2023-01-01 INFO Line 1\n" +
                "2023-01-01 INFO Line 2\n" +
                " Exception in thread main\n" +
                " \tat com.example.Main.main(Main.java:1)\n" +
                "2023-01-01 INFO Line 3";

        ByteArrayResource resource = new ByteArrayResource(logData.getBytes());
        // Pattern matches timestamp at start
        String pattern = "^\\d{4}-\\d{2}-\\d{2}";

        MultilineLogItemReader reader = new MultilineLogItemReader(resource, pattern);

        // Record 1
        String record1 = reader.read();
        assertEquals("2023-01-01 INFO Line 1", record1);

        // Record 2 (Multiline)
        String record2 = reader.read();
        String expected2 = "2023-01-01 INFO Line 2" + System.lineSeparator() +
                " Exception in thread main" + System.lineSeparator() +
                " \tat com.example.Main.main(Main.java:1)";
        assertEquals(expected2, record2);

        // Record 3
        String record3 = reader.read();
        assertEquals("2023-01-01 INFO Line 3", record3);

        // End
        assertNull(reader.read());

        reader.close();
    }
}
