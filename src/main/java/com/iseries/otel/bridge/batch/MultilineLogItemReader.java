package com.iseries.otel.bridge.batch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.NonTransientResourceException;
import org.springframework.batch.item.ParseException;
import org.springframework.batch.item.UnexpectedInputException;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * A custom ItemReader that reads multiline log records. It assumes a record starts with a line
 * matching the provided regex pattern. All subsequent lines that do NOT match the pattern are
 * appended to the previous record.
 */
public class MultilineLogItemReader implements ItemReader<String> {

    private final Resource resource;
    private final Pattern startPattern;
    private final List<String> inMemoryLines;
    private final int inMemoryLineCount;
    private BufferedReader reader;
    private String nextLine; // Buffer for the peeked line
    private int currentIndex = 0;

    /** Constructor for file-based reading. */
    public MultilineLogItemReader(Resource resource, String patternRegex) {
        Assert.notNull(resource, "Resource must not be null");
        Assert.hasText(patternRegex, "Pattern regex must not be empty");
        this.resource = resource;
        this.startPattern = Pattern.compile(patternRegex);
        this.inMemoryLines = null;
        this.inMemoryLineCount = 0;
    }

    /**
     * Constructor for in-memory String list reading (e.g., from Kafka consumer). Each string in the
     * list is treated as a line.
     */
    public MultilineLogItemReader(List<String> lines, String patternRegex) {
        Assert.notNull(lines, "Lines must not be null");
        Assert.hasText(patternRegex, "Pattern regex must not be empty");
        this.resource = null;
        this.startPattern = Pattern.compile(patternRegex);
        this.inMemoryLines = new ArrayList<>(lines);
        this.inMemoryLineCount = inMemoryLines.size();
    }

    @Nullable
    @Override
    public String read()
            throws Exception,
                    UnexpectedInputException,
                    ParseException,
                    NonTransientResourceException {
        // Handle in-memory mode
        if (inMemoryLines != null) {
            return readFromMemory();
        }

        // Handle file-based mode
        if (reader == null) {
            open();
        }

        if (nextLine == null) {
            return null; // End of file
        }

        StringBuilder record = new StringBuilder();
        record.append(nextLine); // Start with the pre-read line (which is the start of this record)

        // Read ahead to find full record
        while (true) {
            String line = reader.readLine();
            if (line == null) {
                // EOF reached
                nextLine = null;
                break;
            }

            if (startPattern.matcher(line).find()) {
                // Found start of NEXT record
                nextLine = line; // Save for next read() call
                break; // Current record is done
            } else {
                // Continuation of current record
                record.append(System.lineSeparator()).append(line);
            }
        }

        return record.toString();
    }

    /** Read from in-memory list (used by Kafka consumer). */
    private String readFromMemory() {
        if (currentIndex >= inMemoryLineCount) {
            return null; // End of in-memory content
        }

        String line = inMemoryLines.get(currentIndex);
        currentIndex++;

        StringBuilder record = new StringBuilder();
        record.append(line);

        // Continue reading continuation lines
        while (currentIndex < inMemoryLineCount) {
            String nextLineToCheck = inMemoryLines.get(currentIndex);

            if (startPattern.matcher(nextLineToCheck).find()) {
                // Found start of next record - don't consume it yet
                break;
            } else {
                // Continuation line
                record.append(System.lineSeparator()).append(nextLineToCheck);
                currentIndex++;
            }
        }

        return record.toString();
    }

    private void open() throws IOException {
        if (resource.exists()) {
            reader = new BufferedReader(new InputStreamReader(resource.getInputStream()));
            // Prime the buffer with the first line
            nextLine = reader.readLine();
            // If the first line doesn't match pattern, we technically have "garbage" at
            // start,
            // but we treat it as a record or part of one.
            // For robustness, if it's not null, we just start consuming.
            // (A strict impl might skip until first match, but let's be lenient for legacy
            // logs)
        } else {
            // If resource doesn't exist, treat as empty
            nextLine = null;
        }
    }

    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
    }
}
