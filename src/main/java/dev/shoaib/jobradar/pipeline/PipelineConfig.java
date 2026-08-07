package dev.shoaib.jobradar.pipeline;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} (used by {@link IngestOrchestrator}'s hourly run)
 * and {@code @Retryable} (used by {@link ThrottledHttpFetcher}'s 5xx/timeout
 * retry). Kept as its own small config class inside {@code pipeline} rather
 * than touching the frozen {@code JobRadarApplication} root class.
 */
@Configuration
@EnableScheduling
@EnableRetry
public class PipelineConfig {
}
