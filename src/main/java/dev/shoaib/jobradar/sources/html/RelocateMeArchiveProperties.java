package dev.shoaib.jobradar.sources.html;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.relocateme-archive.*} (see application.yml). Base URL and paging come
 * from {@link RelocateMeSubstackProperties}; this only controls the full-archive job.
 *
 * @param issueTitlePattern archive posts whose title matches this regex are weekly
 *     job-list issues and get archived
 * @param cron when the scheduled archive pass runs; {@code -} disables it
 */
@ConfigurationProperties(prefix = "app.relocateme-archive")
public record RelocateMeArchiveProperties(String issueTitlePattern, String cron) {
}
