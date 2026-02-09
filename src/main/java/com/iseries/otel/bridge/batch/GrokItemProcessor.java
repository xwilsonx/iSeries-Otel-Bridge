package com.iseries.otel.bridge.batch;

import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import io.krakens.grok.api.Match;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GrokItemProcessor implements ItemProcessor<String, Map<String, Object>> {

    private final Grok grok;

    public GrokItemProcessor(String grokPattern) {
        GrokCompiler compiler = GrokCompiler.newInstance();
        compiler.registerDefaultPatterns();
        this.grok = compiler.compile(grokPattern);
    }

    @Override
    public Map<String, Object> process(String item) {
        if (item == null || item.trim().isEmpty()) {
            return null;
        }

        Match match = grok.match(item);
        Map<String, Object> capture = match.capture();

        if (capture == null || capture.isEmpty()) {
            log.warn("Failed to parse log line with Grok pattern: {}", item);
            // Fallback: return the raw message with an error flag
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("message", item);
            fallback.put("parse_error", true);
            return fallback;
        }

        return capture;
    }
}
