package dev.shoaib.jobradar.sources.html;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@code --app.archive-relocateme=true}: run one full {@link RelocateMeWeeklyArchiver} pass,
 * print the summary, then exit -- a one-off backfill, like {@code --app.detect-ats}.
 */
@Component
@Order(Integer.MIN_VALUE + 1)
public class RelocateMeArchiveRunner implements ApplicationRunner {

    private final RelocateMeWeeklyArchiver archiver;
    private final boolean archiveRequested;

    public RelocateMeArchiveRunner(RelocateMeWeeklyArchiver archiver,
        @Value("${app.archive-relocateme:false}") boolean archiveRequested) {
        this.archiver = archiver;
        this.archiveRequested = archiveRequested;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!archiveRequested) {
            return;
        }
        System.out.println("relocateme-archive: " + archiver.archive());
        System.exit(0);
    }
}
