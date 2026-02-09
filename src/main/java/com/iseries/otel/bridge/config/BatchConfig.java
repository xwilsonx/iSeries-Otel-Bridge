package com.iseries.otel.bridge.config;

import com.iseries.otel.bridge.batch.GrokItemProcessor;
import com.iseries.otel.bridge.batch.MultilineLogItemReader;
import com.iseries.otel.bridge.batch.OtelItemWriter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.File;
import java.util.Map;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class BatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final SdkLoggerProvider sdkLoggerProvider;

    @Bean
    public Job logConversionJob(Step logConversionStep) {
        return new JobBuilder("logConversionJob", jobRepository)
                .start(logConversionStep)
                .build();
    }

    @Bean
    public Step logConversionStep(ItemReader<String> reader,
            ItemProcessor<String, Map<String, Object>> processor,
            ItemWriter<Map<String, Object>> writer) {
        return new StepBuilder("logConversionStep", jobRepository)
                .<String, Map<String, Object>>chunk(100, transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(10)
                .build();
    }

    @Bean
    @StepScope
    public MultilineLogItemReader reader(@Value("#{jobParameters['input.file.path']}") String filePath,
            @Value("#{jobParameters['input.file.pattern']}") String pattern) {
        if (filePath == null) {
            return null;
        }

        // Default pattern if finding fails or is empty, though in prod we expect a
        // pattern
        String effectivePattern = (pattern != null && !pattern.isEmpty()) ? pattern : "^.*";

        return new MultilineLogItemReader(new FileSystemResource(new File(filePath)), effectivePattern);
    }

    @Bean
    @StepScope
    public GrokItemProcessor processor(@Value("#{jobParameters['parser.grok.pattern']}") String grokPattern) {
        return new GrokItemProcessor(grokPattern);
    }

    @Bean
    public OtelItemWriter writer() {
        return new OtelItemWriter(sdkLoggerProvider);
    }
}
