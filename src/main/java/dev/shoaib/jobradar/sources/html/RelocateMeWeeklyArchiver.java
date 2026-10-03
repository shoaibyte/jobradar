package dev.shoaib.jobradar.sources.html;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.persistence.RelocateMeIssueEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeIssueRepository;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobEntity;
import dev.shoaib.jobradar.core.persistence.RelocateMeJobRepository;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.IssueEntries;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.PostRef;
import dev.shoaib.jobradar.sources.html.RelocateMeSubstackAdapter.SectionEntry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps a full copy of every weekly job-list issue of The Global Move in
 * {@code relocateme_issue} / {@code relocateme_job}: all sections, one row per entry
 * per issue, no cross-week dedupe. Separate from the hourly match feed, which reads only
 * the last few issues and only the configured sections.
 *
 * <p>Pages the whole {@code /api/v1/archive} (no lookback cutoff) through the polite
 * {@link HttpFetcher}. An issue already stored as {@code FULL} is skipped; a
 * {@code PREVIEW} (paid issue served without a working session cookie) is re-fetched
 * only when a cookie is configured, since anonymously it would come back the same.
 * Each issue is written in its own transaction, replacing any rows it had before.
 *
 * <p>The first issues embed each section as a Datawrapper table rather than a list; those
 * rows are read from the table's public {@code dataset.csv}. If any table fails to load
 * the issue is stored as {@code PARTIAL} and retried on the next pass.
 */
@Service
public class RelocateMeWeeklyArchiver {

    private static final Logger log = LoggerFactory.getLogger(RelocateMeWeeklyArchiver.class);
    private static final Pattern WEEK_NUMBER = Pattern.compile("(?i)\\bweek\\s+(\\d+)");

    private final RelocateMeSubstackAdapter adapter;
    private final RelocateMeSubstackProperties sourceProps;
    private final Pattern issueTitlePattern;
    private final HttpFetcher http;
    private final RelocateMeIssueRepository issues;
    private final RelocateMeJobRepository jobs;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReentrantLock runLock = new ReentrantLock();

    public RelocateMeWeeklyArchiver(RelocateMeSubstackAdapter adapter, RelocateMeSubstackProperties sourceProps,
        RelocateMeArchiveProperties archiveProps, HttpFetcher http, RelocateMeIssueRepository issues,
        RelocateMeJobRepository jobs, PlatformTransactionManager txManager) {
        this.adapter = adapter;
        this.sourceProps = sourceProps;
        this.issueTitlePattern = Pattern.compile(archiveProps.issueTitlePattern());
        this.http = http;
        this.issues = issues;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(cron = "${app.relocateme-archive.cron:-}")
    public void scheduledArchive() {
        archive();
    }

    /** Archives every weekly issue not yet stored in full. Concurrent calls skip. */
    public Summary archive() {
        if (!runLock.tryLock()) {
            log.info("relocateme-archive: a pass is already running, skipping");
            return new Summary(0, 0, 0, 0, 0, 0);
        }
        try {
            return doArchive();
        } finally {
            runLock.unlock();
        }
    }

    private Summary doArchive() {
        List<PostRef> weekly = discoverWeeklyIssues();
        boolean cookie = adapter.cookieConfigured();
        int full = 0;
        int preview = 0;
        int empty = 0;
        int skipped = 0;
        int failed = 0;
        for (PostRef post : weekly) {
            Optional<RelocateMeIssueEntity> existing = issues.findBySlug(post.slug());
            if (existing.isPresent() && !needsRefetch(existing.get(), cookie)) {
                skipped++;
                continue;
            }
            try {
                Optional<String> json = http.tryGet(sourceProps.baseUrl() + "/api/v1/posts/" + post.slug());
                if (json.isEmpty()) {
                    log.warn("relocateme-archive: post {} vanished (404), skipping", post.slug());
                    failed++;
                    continue;
                }
                IssueEntries parsed = adapter.parseAllEntries(json.get());
                TableFetch tables = fetchEmbeddedTables(post, parsed);
                RelocateMeIssueEntity saved = store(post, tables.merged(), existing, tables.failures() > 0);
                switch (saved.getBodyStatus()) {
                    case RelocateMeIssueEntity.FULL -> full++;
                    case RelocateMeIssueEntity.PREVIEW -> preview++;
                    case RelocateMeIssueEntity.PARTIAL -> failed++;
                    default -> empty++;
                }
                log.info("relocateme-archive: {} (week {}) -> {} with {} job(s)", post.slug(),
                    saved.getWeekNumber(), saved.getBodyStatus(), saved.getJobCount());
            } catch (Exception e) {
                failed++;
                log.warn("relocateme-archive: failed on {}: {}", post.slug(), e.toString());
            }
        }
        Summary summary = new Summary(weekly.size(), full, preview, empty, skipped, failed);
        log.info("relocateme-archive: {}", summary);
        if (preview > 0 && !cookie) {
            log.info("relocateme-archive: {} paid issue(s) stored as preview only. Set SUBSTACK_COOKIE to a paid "
                + "subscriber's session cookie and re-run to fill them in.", preview);
        }
        return summary;
    }

    private static boolean needsRefetch(RelocateMeIssueEntity issue, boolean cookieConfigured) {
        return switch (issue.getBodyStatus()) {
            case RelocateMeIssueEntity.FULL -> false;
            case RelocateMeIssueEntity.PREVIEW -> cookieConfigured;
            default -> true;
        };
    }

    /** Whole archive, newest-first, filtered to weekly job-list issues by title. */
    List<PostRef> discoverWeeklyIssues() {
        List<PostRef> selected = new ArrayList<>();
        int offset = 0;
        while (true) {
            String url = sourceProps.baseUrl() + "/api/v1/archive?sort=new&offset=" + offset
                + "&limit=" + sourceProps.archivePageSize();
            List<PostRef> page;
            try {
                Optional<String> json = http.tryGet(url);
                if (json.isEmpty()) {
                    break;
                }
                page = adapter.parseArchivePage(json.get());
            } catch (Exception e) {
                log.warn("relocateme-archive: archive page at offset {} failed, stopping discovery: {}",
                    offset, e.toString());
                break;
            }
            if (page.isEmpty()) {
                break;
            }
            for (PostRef post : page) {
                if (issueTitlePattern.matcher(post.title()).find()) {
                    selected.add(post);
                }
            }
            offset += page.size();
        }
        log.info("relocateme-archive: discovered {} weekly issue(s)", selected.size());
        return selected;
    }

    /**
     * Fetches each embedded Datawrapper table and appends its rows to the HTML entries,
     * numbering positions on from whatever the section already had.
     */
    private TableFetch fetchEmbeddedTables(PostRef post, IssueEntries parsed) {
        if (parsed.tables().isEmpty()) {
            return new TableFetch(parsed, 0);
        }
        List<SectionEntry> entries = new ArrayList<>(parsed.entries());
        Map<String, Integer> positions = new HashMap<>();
        for (SectionEntry e : entries) {
            positions.merge(e.section(), e.position(), Math::max);
        }
        int failures = 0;
        for (RelocateMeSubstackAdapter.EmbeddedTable table : parsed.tables()) {
            try {
                Optional<String> csv = http.tryGet(table.csvUrl());
                if (csv.isEmpty()) {
                    failures++;
                    log.warn("relocateme-archive: {} table for '{}' not found: {}", post.slug(), table.section(),
                        table.csvUrl());
                    continue;
                }
                for (RelocateMeSubstackAdapter.Entry entry : RelocateMeSubstackAdapter.entriesFromCsv(csv.get())) {
                    int position = positions.merge(table.section(), 1, Integer::sum);
                    entries.add(new SectionEntry(table.section(), position, entry));
                }
            } catch (Exception e) {
                failures++;
                log.warn("relocateme-archive: {} table for '{}' failed: {}", post.slug(), table.section(),
                    e.toString());
            }
        }
        return new TableFetch(new IssueEntries(parsed.audience(), parsed.subtitle(), entries,
            parsed.advertisedCounts(), parsed.tables()), failures);
    }

    private record TableFetch(IssueEntries merged, int failures) {
    }

    RelocateMeIssueEntity store(PostRef post, IssueEntries parsed, Optional<RelocateMeIssueEntity> existing,
        boolean incomplete) {
        return tx.execute(status -> {
            RelocateMeIssueEntity issue = existing.orElseGet(RelocateMeIssueEntity::new);
            issue.setSlug(post.slug());
            issue.setWeekNumber(weekNumber(post.title()));
            issue.setTitle(post.title());
            issue.setSubtitle(parsed.subtitle().isBlank() ? null : parsed.subtitle());
            issue.setPostDate(post.postDate().toString());
            issue.setAudience(parsed.audience().isBlank() ? post.audience() : parsed.audience());
            issue.setBodyStatus(incomplete ? RelocateMeIssueEntity.PARTIAL : bodyStatus(parsed, issue.getAudience()));
            issue.setAdvertisedCounts(toJson(parsed.advertisedCounts()));
            issue.setJobCount(parsed.entries().size());
            issue.setScrapedAt(clock.instant().toString());
            RelocateMeIssueEntity saved = issues.save(issue);

            jobs.deleteByIssueId(saved.getId());
            List<RelocateMeJobEntity> rows = new ArrayList<>();
            for (SectionEntry se : parsed.entries()) {
                rows.add(toRow(saved.getId(), se));
            }
            jobs.saveAll(rows);
            return saved;
        });
    }

    private RelocateMeJobEntity toRow(Integer issueId, SectionEntry se) {
        RelocateMeSubstackAdapter.Entry e = se.entry();
        RelocateMeJobEntity row = new RelocateMeJobEntity();
        row.setIssueId(issueId);
        row.setSection(se.section().isBlank() ? "(none)" : se.section());
        row.setPosition(se.position());
        row.setTitle(e.title());
        row.setCompany(e.company());
        row.setLocation(e.location());
        row.setCity(e.city());
        row.setCountry(e.country());
        row.setIndustrySize(e.industrySize());
        row.setKeywords(toJson(e.keywords()));
        row.setApplyUrl(e.applyUrl());
        row.setCanonicalUrl(RelocateMeSubstackAdapter.canonicalUrl(e.applyUrl()));
        row.setVisaMentioned(!e.visaLines().isEmpty());
        row.setDetails(String.join("\n", e.details()));
        row.setRemote(e.where().remote());
        row.setRemoteRegion(e.where().remoteRegion());
        row.setCountryCodes(toJson(e.where().countryCodes()));
        row.setCompanyLinkedinUrl(e.companyLinkedinUrl());
        return row;
    }

    /**
     * FULL when entries were parsed and, for a paid issue, at least one section its intro
     * advertises is actually present -- the anonymous preview of a paid issue can carry a
     * "sneak peek" list under its own heading, which must not pass for the full body.
     * PREVIEW for a paid issue otherwise (sneak-peek rows are still stored); EMPTY for a
     * free issue with no entry-shaped lists.
     */
    static String bodyStatus(IssueEntries parsed, String audience) {
        boolean paid = audience != null && !"everyone".equals(audience);
        if (parsed.entries().isEmpty()) {
            return paid ? RelocateMeIssueEntity.PREVIEW : RelocateMeIssueEntity.EMPTY;
        }
        if (!paid || parsed.advertisedCounts().isEmpty()) {
            return RelocateMeIssueEntity.FULL;
        }
        boolean advertisedSectionPresent = parsed.entries().stream()
            .map(e -> RelocateMeSubstackAdapter.normalize(e.section()))
            .anyMatch(section -> parsed.advertisedCounts().keySet().stream().anyMatch(section::startsWith));
        return advertisedSectionPresent ? RelocateMeIssueEntity.FULL : RelocateMeIssueEntity.PREVIEW;
    }

    static Integer weekNumber(String title) {
        Matcher m = WEEK_NUMBER.matcher(title == null ? "" : title);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Summary(int discovered, int full, int preview, int empty, int skipped, int failed) {
    }
}
