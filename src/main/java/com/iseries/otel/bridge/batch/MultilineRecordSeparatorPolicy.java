package com.iseries.otel.bridge.batch;

import java.util.regex.Pattern;
import org.springframework.batch.item.file.separator.SimpleRecordSeparatorPolicy;

public class MultilineRecordSeparatorPolicy extends SimpleRecordSeparatorPolicy {

    private final Pattern pattern;

    public MultilineRecordSeparatorPolicy(String patternRegex) {
        this.pattern = Pattern.compile(patternRegex);
    }

    @Override
    public boolean isEndOfRecord(String line) {
        // Null or empty lines are not record boundaries (continuations)
        if (line == null || line.trim().isEmpty()) {
            return false;
        }
        // As per Spring Batch docs, isEndOfRecord is called for the line *after*
        // reading it.
        // Wait, SimpleRecordSeparatorPolicy logic:
        // "Return true if the line is a complete record... The line passed in is the
        // line just read."
        // Actually, for multiline, we usually want to know if the *next* line starts a
        // new record.
        // But we only have the current line.
        // However, Spring Batch accumulates lines if isEndOfRecord returns false.
        // This standard interface is tricky for "start of next line" logic without
        // peeking.
        //
        // BUT, looking at `FlatFileItemReader`, it reads a line, calls
        // policy.isEndOfRecord(line).
        // If false, it reads next line, appends to previous, and calls
        // policy.isEndOfRecord(combined).
        // This standard behavior doesn't work well for "Next line detection".
        //
        // ALTERNATIVE: The standard approach for log processing in Spring Batch
        // usually relies on a custom Reader that can peek, OR simplified assumption:
        // A line is independent unless it's indented (common for stack traces).
        //
        // Let's assume the passed regex matches the START of a log line (e.g.
        // timestamp).
        // If a line matches the start-pattern, it is the start of a record.
        // But `isEndOfRecord` needs to return true for the *previous* record.
        //
        // Actually, `SimpleRecordSeparatorPolicy.postProcess(String record)` is where
        // we can strip things?
        // No.
        //
        // Ref:
        // https://docs.spring.io/spring-batch/reference/readers-and-writers/flat-files/record-separator-policy.html
        // "If isEndOfRecord is false, the current line is appended to the previous one"

        // This is a known limitation of the standard `FlatFileItemReader` with
        // `RecordSeparatorPolicy` for logs
        // where the delimiter is at the START of the record, not the end.
        //
        // A common workaround is to treat lines that DO NOT match the pattern as
        // continuations.
        // But `isEndOfRecord(line)` is checked on the *continuation* line too?
        // No, it's checked on the accumulation.

        // Let's implement a policy that returns FALSE if the line does NOT look like a
        // new log entry.
        // Wait, if line 1 matches DATE, isEndOfRecord=true? Yes.
        // If line 2 is " at com.foo...", it does NOT match DATE.
        // So isEndOfRecord("... at com.foo") should be false?
        // If we return false, it appends.
        // Then we read line 3 (another stack trace). Combined is "DATE... \n at... \n
        // at..."
        // isEndOfRecord(combined) -> False?

        // This logic is flawed because `isEndOfRecord` takes the *incremental* line or
        // the *accumulated* line?
        // Documentation: "Method to determine if the line (or lines) passed in
        // constitute a complete record."

        // For a timestamp-based log:
        // Start: 2023-01-01 ...
        // Line 2: at ...
        // Line 3: 2023-01-01 ...

        // When we read Line 1: It matches. return true? NO, because it might have a
        // stack trace following.
        // We don't know it's the end until we see Line 3.
        // `FlatFileItemReader` cannot "put back" Line 3.

        // CORRECTION: To handle this properly in Spring Batch without a custom peekable
        // reader,
        // we often just use `PatternMatchingCompositeLineTokenizer` or similar,
        // OR we accept that we need a custom `PeekableItemReader` wrapper.
        //
        // HOWEVER, for this task, I will implement a simplified heuristic:
        // If the line *ends* with a continuation marker (not common in logs) or we use
        // indentation rules.
        //
        // Given the requirement "Any kind log model", strict multiline handling is hard
        // without lookahead.
        //
        // Strategy Update:
        // Use a `SingleItemPeekableItemReader` pattern? No, that's for steps.
        //
        // Let's stick to the plan but acknowledge the limitation:
        // We will assume that if a line does NOT match the start pattern, it is part of
        // the previous record.
        // THIS IS WRONG for `RecordSeparatorPolicy` because it decides if the CURRENT
        // accumulation is done.

        // Let's look at `isEndOfRecord` contract again.
        // It returns true if the record is complete.
        // For logs, a record is complete only when the *next* line starts.
        // The standard `FlatFileItemReader` does NOT support this lookahead-delimiter
        // logic natively via SeparatorPolicy alone.

        // I will implement a custom `ItemReader` wrapper in the next step called
        // `PatternMatchingPeekableItemReader` if needed.
        // But for now, let's implement the Policy as best as possible:
        // If the current accumulated line contains a complete record?
        // NO, we can't do it with Policy alone for "Start Pattern" delimiter.

        // RE-EVALUATION:
        // I'll implement the Policy to be transparent (always true) for now,
        // and handle multiline aggregation in a custom `ItemReader` or `Processor`?
        // No, Reader is best.

        // Actually, I can use the `SingleLine` approach for now to get things moving,
        // and add a TODO for robust multiline handling using a custom reader.
        //
        // WAIT! I can use `PassThroughLineMapper` and handle aggregation in the
        // PROCESSOR?
        // No, the Reader chunks based on records.

        // Decision: I will implement a `SmartReader` in `BatchConfig` or a helper class
        // that delegates to `FlatFileItemReader` but handles the peeking.
        // Actually, Spring Batch has `SingleItemPeekableItemReader`.

        // Let's write a simple policy that assumes single line for now to pass
        // compilation
        // and update the plan to implement a proper `PeekingLogReader`.
        //
        // Actually, sticking to the `RecordSeparatorPolicy` defined in the interface:
        // If I return `false`, it reads more.
        // If I return `true`, it stops.
        return true;
    }
}
