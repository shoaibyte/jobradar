package dev.shoaib.jobradar.pipeline;

import dev.shoaib.jobradar.core.IngestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs one ingest pass automatically on startup, in addition to the hourly
 * {@code @Scheduled} run in {@link IngestOrchestrator}. Two modes layered on
 * top of that startup pass:
 *
 * <ul>
 *   <li>{@code --app.run-once=true}: run once, then exit the JVM.</li>
 *   <li>{@code --app.detect-ats=<url>}: skip our own startup run entirely --
 *   the {@code sources-api} agent's detect-ats runner calls {@code System.exit}
 *   before this would matter, but we check defensively so ordering between
 *   {@code ApplicationRunner} beans is never a correctness concern.</li>
 *   <li>{@code --app.archive-relocateme=true}: skipped likewise, for the same reason.</li>
 * </ul>
 */
@Component
public class IngestStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestStartupRunner.class);

    private final IngestRunner ingestRunner;

    @Value("${app.run-once:false}")
    private boolean runOnce;

    @Value("${app.detect-ats:}")
    private String detectAts;

    @Value("${app.archive-relocateme:false}")
    private boolean archiveRelocateMe;

    @Value("${app.startup-run.enabled:true}")
    private boolean startupRunEnabled;

    public IngestStartupRunner(IngestRunner ingestRunner) {
        this.ingestRunner = ingestRunner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (detectAts != null && !detectAts.isBlank()) {
            log.info("app.detect-ats is set; skipping pipeline startup run");
            return;
        }
        if (archiveRelocateMe) {
            log.info("app.archive-relocateme=true; skipping pipeline startup run");
            return;
        }
        if (!startupRunEnabled) {
            log.info("app.startup-run.enabled=false; skipping pipeline startup run");
            return;
        }

        log.info("Running startup ingest pass...");
        Integer runId = ingestRunner.runOnce();
        log.info("Startup ingest pass complete, runId={}", runId);

        if (runOnce) {
            log.info("app.run-once=true; exiting after single pass");
            System.exit(0);
        }
    }
}
