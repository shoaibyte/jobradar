package dev.shoaib.jobradar.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.FetchContext;
import dev.shoaib.jobradar.core.Fingerprint;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.IngestRunner;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchEngine;
import dev.shoaib.jobradar.core.MatchOutcome;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.persistence.IngestRunEntity;
import dev.shoaib.jobradar.core.persistence.IngestRunRepository;
import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.core.persistence.MatchResultEntity;
import dev.shoaib.jobradar.core.persistence.MatchResultRepository;
import dev.shoaib.jobradar.notify.MatchNotification;
import dev.shoaib.jobradar.notify.NotificationChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The one {@code core.IngestRunner} bean (Section 8/9). Fans out over enabled
 * {@link JobSourceAdapter}s on virtual threads, upserts/dedupes into
 * {@code job_record}, evaluates matches, notifies, logs run stats, and exports
 * the current active matches -- see {@code notes/pipeline-notify.md} for the
 * design write-up.
 */
@Service
public class IngestOrchestrator implements IngestRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrator.class);

    private final List<JobSourceAdapter> adapters;
    private final HttpFetcher httpFetcher;
    private final CompanyRegistry companyRegistry;
    private final MatchEngine matchEngine;
    private final JobRecordRepository jobRecordRepository;
    private final MatchResultRepository matchResultRepository;
    private final IngestRunRepository ingestRunRepository;
    private final List<NotificationChannel> notificationChannels;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * Guards against the hourly {@code @Scheduled} run and a manual
     * {@code POST /api/run} (via {@code IngestRunner.runOnce()}) firing at the same
     * moment (Phase 2 fix -- flagged as a known gap in notes/pipeline-notify.md).
     * A concurrent call skips rather than blocks, since a run that's already in
     * flight makes a second one redundant.
     */
    private final ReentrantLock runLock = new ReentrantLock();

    @Value("${app.dry-run:false}")
    private boolean dryRun;

    @Value("${app.export.dir:export}")
    private String exportDir;

    public IngestOrchestrator(List<JobSourceAdapter> adapters, HttpFetcher httpFetcher,
        CompanyRegistry companyRegistry, MatchEngine matchEngine,
        JobRecordRepository jobRecordRepository, MatchResultRepository matchResultRepository,
        IngestRunRepository ingestRunRepository, List<NotificationChannel> notificationChannels) {
        this.adapters = adapters;
        this.httpFetcher = httpFetcher;
        this.companyRegistry = companyRegistry;
        this.matchEngine = matchEngine;
        this.jobRecordRepository = jobRecordRepository;
        this.matchResultRepository = matchResultRepository;
        this.ingestRunRepository = ingestRunRepository;
        this.notificationChannels = notificationChannels;
    }

    @Scheduled(cron = "${app.schedule.cron:0 7 * * * *}")
    public void scheduledRun() {
        log.info("Scheduled ingest run starting");
        Integer runId = runOnce();
        log.info("Scheduled ingest run finished, runId={}", runId);
    }

    @Override
    public Integer runOnce() {
        if (!runLock.tryLock()) {
            log.warn("Ingest run already in progress; skipping this trigger (scheduled run vs. manual "
                + "POST /api/run overlap)");
            return null;
        }
        try {
            return doRunOnce();
        } finally {
            runLock.unlock();
        }
    }

    private Integer doRunOnce() {
        Instant startedAt = Instant.now();
        IngestRunEntity run = new IngestRunEntity();
        run.setStartedAt(startedAt.toString());
        Integer runId = null;
        if (!dryRun) {
            run = ingestRunRepository.save(run);
            runId = run.getId();
        } else {
            log.info("[dry-run] ingest run starting at {}", startedAt);
        }

        List<JobSourceAdapter> enabled = enabledAdapters();
        FetchContext ctx = new FetchContext(httpFetcher, companyRegistry, Clock.systemUTC());

        Map<String, List<JobPosting>> postingsBySource = new ConcurrentHashMap<>();
        Set<String> failedSources = ConcurrentHashMap.newKeySet();
        Map<String, SourceStats> statsBySource = new ConcurrentHashMap<>();

        fanOutFetch(enabled, ctx, postingsBySource, failedSources, statsBySource);

        List<PendingMatch> pendingMatches = new ArrayList<>();
        for (JobSourceAdapter adapter : enabled) {
            processSource(adapter, postingsBySource, failedSources, statsBySource, pendingMatches);
        }

        int matchesEvaluated = 0;
        for (PendingMatch pm : pendingMatches) {
            Optional<MatchOutcome> outcome = matchEngine.evaluate(pm.posting());
            if (outcome.isEmpty()) {
                continue;
            }
            matchesEvaluated++;
            if (!dryRun) {
                upsertMatch(pm.jobRecord().getId(), outcome.get());
            }
        }

        if (dryRun) {
            log.info("[dry-run] fetched/new/updated/removed/errors per source: {}", statsBySource);
            log.info("[dry-run] {} postings evaluated for matching, {} passed gates",
                pendingMatches.size(), matchesEvaluated);
            log.info("[dry-run] no DB writes, notifications, or export performed");
            return null;
        }

        notifyUnnotifiedMatches();

        run.setFinishedAt(Instant.now().toString());
        run.setStats(toStatsJson(statsBySource));
        ingestRunRepository.save(run);

        exportActiveMatches();

        return runId;
    }

    private List<JobSourceAdapter> enabledAdapters() {
        List<JobSourceAdapter> result = new ArrayList<>();
        for (JobSourceAdapter adapter : adapters) {
            try {
                if (adapter.enabled()) {
                    result.add(adapter);
                }
            } catch (RuntimeException e) {
                log.warn("adapter.enabled() threw for {}, skipping", safeName(adapter), e);
            }
        }
        return result;
    }

    private void fanOutFetch(List<JobSourceAdapter> enabled, FetchContext ctx,
        Map<String, List<JobPosting>> postingsBySource, Set<String> failedSources,
        Map<String, SourceStats> statsBySource) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (JobSourceAdapter adapter : enabled) {
                futures.add(executor.submit(
                    () -> fetchAdapter(adapter, ctx, postingsBySource, failedSources, statsBySource)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("Unexpected adapter task failure", e.getCause());
                }
            }
        }
    }

    private void fetchAdapter(JobSourceAdapter adapter, FetchContext ctx,
        Map<String, List<JobPosting>> postingsBySource, Set<String> failedSources,
        Map<String, SourceStats> statsBySource) {
        String name = adapter.name();
        SourceStats stats = new SourceStats();
        try {
            List<JobPosting> postings = adapter.fetch(ctx);
            postingsBySource.put(name, postings);
            stats.fetched = postings.size();
        } catch (SourceFetchException e) {
            log.error("Adapter {} failed: {}", name, e.getMessage(), e);
            stats.errors = 1;
            failedSources.add(name);
        } catch (RuntimeException e) {
            log.error("Adapter {} threw unexpected exception: {}", name, e.getMessage(), e);
            stats.errors = 1;
            failedSources.add(name);
        } finally {
            statsBySource.put(name, stats);
        }
    }

    private void processSource(JobSourceAdapter adapter, Map<String, List<JobPosting>> postingsBySource,
        Set<String> failedSources, Map<String, SourceStats> statsBySource, List<PendingMatch> pendingMatches) {
        String sourceName = adapter.name();
        List<JobPosting> postings = postingsBySource.getOrDefault(sourceName, List.of());
        SourceStats stats = statsBySource.computeIfAbsent(sourceName, k -> new SourceStats());
        Set<String> currentExternalIds = new HashSet<>();

        for (JobPosting posting : postings) {
            currentExternalIds.add(posting.externalId());
            UpsertOutcome outcome = upsert(sourceName, posting);
            if (outcome.isNew()) {
                stats.newCount++;
            } else if (outcome.changed()) {
                stats.updated++;
            }
            if (outcome.isNew() || outcome.changed()) {
                pendingMatches.add(new PendingMatch(outcome.jobRecord(), posting));
            }
        }

        boolean eligibleForRemoval = !failedSources.contains(sourceName) && adapter.supportsRemovalDetection();
        if (!eligibleForRemoval) {
            return;
        }
        List<JobRecordEntity> active = jobRecordRepository.findBySourceAndStatus(sourceName, JobStatus.ACTIVE);
        List<JobRecordEntity> toRemove = new ArrayList<>();
        for (JobRecordEntity rec : active) {
            if (!currentExternalIds.contains(rec.getExternalId())) {
                toRemove.add(rec);
            }
        }
        stats.removed = toRemove.size();
        if (dryRun) {
            if (!toRemove.isEmpty()) {
                log.info("[dry-run] {} would mark {} record(s) REMOVED", sourceName, toRemove.size());
            }
            return;
        }
        for (JobRecordEntity rec : toRemove) {
            rec.setStatus(JobStatus.REMOVED);
            jobRecordRepository.save(rec);
        }
    }

    /** Upsert keyed by (source, externalId); see notes/pipeline-notify.md for the "changed" heuristic. */
    private UpsertOutcome upsert(String source, JobPosting posting) {
        Optional<JobRecordEntity> existingOpt = jobRecordRepository.findBySourceAndExternalId(source, posting.externalId());
        String nowStr = Instant.now().toString();
        String fingerprint = Fingerprint.of(posting.company(), posting.title(), posting.country());
        String techTagsJson = toJson(posting.techTags());
        String benefitsJson = toJson(posting.benefits());

        if (existingOpt.isEmpty()) {
            JobRecordEntity rec = new JobRecordEntity();
            rec.setSource(source);
            rec.setExternalId(posting.externalId());
            rec.setFingerprint(fingerprint);
            rec.setTitle(posting.title());
            rec.setCompany(posting.company());
            rec.setCity(posting.city());
            rec.setCountry(posting.country());
            rec.setUrl(posting.url());
            rec.setDescription(posting.description());
            rec.setTechTags(techTagsJson);
            rec.setBenefits(benefitsJson);
            rec.setSalaryRaw(posting.salaryRaw());
            rec.setVisaFlag(posting.visaFlag());
            rec.setPostedAt(posting.postedAt() != null ? posting.postedAt().toString() : null);
            rec.setFirstSeen(nowStr);
            rec.setLastSeen(nowStr);
            rec.setStatus(JobStatus.ACTIVE);
            if (!dryRun) {
                rec = jobRecordRepository.save(rec);
            }
            return new UpsertOutcome(rec, true, false);
        }

        JobRecordEntity rec = existingOpt.get();
        boolean changed = !Objects.equals(rec.getDescription(), posting.description())
            || !Objects.equals(rec.getTechTags(), techTagsJson)
            || !Objects.equals(rec.getBenefits(), benefitsJson)
            || !Objects.equals(rec.getSalaryRaw(), posting.salaryRaw());

        rec.setLastSeen(nowStr);
        rec.setDescription(posting.description());
        rec.setTechTags(techTagsJson);
        rec.setBenefits(benefitsJson);
        rec.setSalaryRaw(posting.salaryRaw());
        rec.setFingerprint(fingerprint);
        if (rec.getStatus() == JobStatus.REMOVED) {
            // Reappeared after being marked removed -- bring it back to ACTIVE.
            rec.setStatus(JobStatus.ACTIVE);
            changed = true;
        }
        if (!dryRun) {
            rec = jobRecordRepository.save(rec);
        }
        return new UpsertOutcome(rec, false, changed);
    }

    /**
     * Only resets {@code notified=false} for a brand-new match so an unchanged
     * match doesn't get re-notified on every run.
     */
    private void upsertMatch(Integer jobId, MatchOutcome outcome) {
        if (jobId == null) {
            return;
        }
        Optional<MatchResultEntity> existing = matchResultRepository.findById(jobId);
        MatchResultEntity entity = existing.orElseGet(MatchResultEntity::new);
        boolean isNew = existing.isEmpty();
        entity.setJobId(jobId);
        entity.setScore(outcome.score());
        entity.setStrength(outcome.strength());
        entity.setReasons(toJson(outcome.reasons()));
        entity.setEvaluatedAt(Instant.now().toString());
        if (isNew) {
            entity.setNotified(false);
        }
        matchResultRepository.save(entity);
    }

    private void notifyUnnotifiedMatches() {
        List<MatchResultEntity> unnotified = matchResultRepository.findUnnotifiedByJobStatus(JobStatus.ACTIVE);
        if (unnotified.isEmpty()) {
            return;
        }
        List<MatchNotification> notifications = new ArrayList<>();
        for (MatchResultEntity match : unnotified) {
            toNotification(match).ifPresent(notifications::add);
        }
        for (NotificationChannel channel : notificationChannels) {
            try {
                channel.notify(notifications);
            } catch (RuntimeException e) {
                log.error("Notification channel {} failed", channel.getClass().getSimpleName(), e);
            }
        }
        for (MatchResultEntity match : unnotified) {
            match.setNotified(true);
        }
        matchResultRepository.saveAll(unnotified);
    }

    private Optional<MatchNotification> toNotification(MatchResultEntity match) {
        Optional<JobRecordEntity> jobOpt = jobRecordRepository.findById(match.getJobId());
        if (jobOpt.isEmpty()) {
            return Optional.empty();
        }
        JobRecordEntity job = jobOpt.get();
        List<String> techTags = fromJson(job.getTechTags());
        return Optional.of(new MatchNotification(match.getStrength().name(), match.getScore(),
            job.getTitle(), job.getCompany(), job.getCity(), job.getCountry(), techTags,
            job.getDescription(), job.getSalaryRaw(), job.getUrl()));
    }

    private void exportActiveMatches() {
        try {
            List<MatchResultEntity> matches = matchResultRepository.search(null, null, JobStatus.ACTIVE);
            Path dir = Path.of(exportDir);
            Files.createDirectories(dir);

            List<Map<String, Object>> jsonRows = new ArrayList<>();
            List<String> csvLines = new ArrayList<>();
            csvLines.add(String.join(",", "jobId", "title", "company", "city", "country",
                "strength", "score", "salaryRaw", "url", "evaluatedAt"));

            for (MatchResultEntity match : matches) {
                Optional<JobRecordEntity> jobOpt = jobRecordRepository.findById(match.getJobId());
                if (jobOpt.isEmpty()) {
                    continue;
                }
                JobRecordEntity job = jobOpt.get();

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jobId", job.getId());
                row.put("title", job.getTitle());
                row.put("company", job.getCompany());
                row.put("city", job.getCity());
                row.put("country", job.getCountry());
                row.put("url", job.getUrl());
                row.put("salaryRaw", job.getSalaryRaw());
                row.put("strength", match.getStrength().name());
                row.put("score", match.getScore());
                row.put("evaluatedAt", match.getEvaluatedAt());
                jsonRows.add(row);

                csvLines.add(String.join(",",
                    csvField(String.valueOf(job.getId())),
                    csvField(job.getTitle()),
                    csvField(job.getCompany()),
                    csvField(job.getCity()),
                    csvField(job.getCountry()),
                    csvField(match.getStrength().name()),
                    csvField(String.valueOf(match.getScore())),
                    csvField(job.getSalaryRaw()),
                    csvField(job.getUrl()),
                    csvField(match.getEvaluatedAt())));
            }

            Files.writeString(dir.resolve("matches.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonRows));
            Files.writeString(dir.resolve("matches.csv"), String.join("\n", csvLines) + "\n");
        } catch (IOException e) {
            log.error("Failed to export matches", e);
        }
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n");
        String escaped = value.replace("\"", "\"\"");
        return needsQuoting ? "\"" + escaped + "\"" : escaped;
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String toStatsJson(Map<String, SourceStats> statsBySource) {
        Map<String, Object> result = new LinkedHashMap<>();
        statsBySource.forEach((k, v) -> result.put(k, Map.of(
            "fetched", v.fetched, "new", v.newCount, "updated", v.updated,
            "removed", v.removed, "errors", v.errors)));
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize run stats", e);
            return "{}";
        }
    }

    private static String safeName(JobSourceAdapter adapter) {
        try {
            return adapter.name();
        } catch (RuntimeException e) {
            return adapter.getClass().getSimpleName();
        }
    }

    /** Per-source counters serialized into {@code ingest_run.stats}. */
    private static final class SourceStats {
        volatile int fetched;
        volatile int newCount;
        volatile int updated;
        volatile int removed;
        volatile int errors;

        @Override
        public String toString() {
            return "{fetched=" + fetched + ", new=" + newCount + ", updated=" + updated
                + ", removed=" + removed + ", errors=" + errors + "}";
        }
    }

    private record UpsertOutcome(JobRecordEntity jobRecord, boolean isNew, boolean changed) {
    }

    private record PendingMatch(JobRecordEntity jobRecord, JobPosting posting) {
    }
}
