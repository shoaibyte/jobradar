package dev.shoaib.jobradar.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchEngine;
import dev.shoaib.jobradar.core.MatchStrength;
import dev.shoaib.jobradar.core.persistence.IngestRunEntity;
import dev.shoaib.jobradar.core.persistence.IngestRunRepository;
import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.core.persistence.MatchResultEntity;
import dev.shoaib.jobradar.core.persistence.MatchResultRepository;
import dev.shoaib.jobradar.notify.NotificationChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/** Covers Section 8 step 8: exporting current ACTIVE matches to matches.json/matches.csv. */
class IngestOrchestratorExportTest {

    private final JobRecordRepository jobRecordRepository = mock(JobRecordRepository.class);
    private final MatchResultRepository matchResultRepository = mock(MatchResultRepository.class);
    private final IngestRunRepository ingestRunRepository = mock(IngestRunRepository.class);
    private final MatchEngine matchEngine = mock(MatchEngine.class);
    private final HttpFetcher httpFetcher = mock(HttpFetcher.class);
    private final CompanyRegistry companyRegistry = mock(CompanyRegistry.class);

    @TempDir
    Path tempDir;

    @Test
    void exportWritesJsonAndCsvForActiveMatches() {
        when(ingestRunRepository.save(any(IngestRunEntity.class))).thenAnswer(inv -> {
            IngestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1);
            }
            return e;
        });
        when(matchResultRepository.findUnnotifiedByJobStatus(any())).thenReturn(List.of());

        JobRecordEntity job = new JobRecordEntity();
        job.setId(42);
        job.setSource("arbeitnow");
        job.setExternalId("ext-42");
        job.setTitle("Backend Engineer, Java");
        job.setCompany("Acme, Inc.");
        job.setCity("Berlin");
        job.setCountry("DE");
        job.setUrl("https://example.com/job/42");
        job.setSalaryRaw("60k-80k");
        job.setStatus(JobStatus.ACTIVE);
        job.setFirstSeen(Instant.now().toString());
        job.setLastSeen(Instant.now().toString());
        when(jobRecordRepository.findById(42)).thenReturn(Optional.of(job));

        MatchResultEntity match = new MatchResultEntity();
        match.setJobId(42);
        match.setScore(8.5);
        match.setStrength(MatchStrength.STRONG);
        match.setReasons("[\"java-in-title\"]");
        match.setEvaluatedAt(Instant.now().toString());
        match.setNotified(true);
        when(matchResultRepository.search(null, null, JobStatus.ACTIVE)).thenReturn(List.of(match));

        IngestOrchestrator orchestrator = new IngestOrchestrator(List.of(), httpFetcher, companyRegistry,
            matchEngine, jobRecordRepository, matchResultRepository, ingestRunRepository, List.of());
        ReflectionTestUtils.setField(orchestrator, "dryRun", false);
        ReflectionTestUtils.setField(orchestrator, "exportDir", tempDir.toString());

        orchestrator.runOnce();

        Path jsonFile = tempDir.resolve("matches.json");
        Path csvFile = tempDir.resolve("matches.csv");
        assertThat(jsonFile).exists();
        assertThat(csvFile).exists();

        String json = readFile(jsonFile);
        assertThat(json).contains("\"jobId\"").contains("42");
        assertThat(json).contains("Backend Engineer, Java").contains("STRONG");

        String csv = readFile(csvFile);
        List<String> lines = csv.lines().toList();
        assertThat(lines.get(0)).isEqualTo("jobId,title,company,city,country,strength,score,salaryRaw,url,evaluatedAt");
        assertThat(lines).hasSize(2);
        // "Acme, Inc." contains a comma so it must be quoted in the CSV row.
        assertThat(lines.get(1)).contains("\"Acme, Inc.\"").contains("STRONG").contains("42");
    }

    @Test
    void exportWithNoActiveMatchesWritesEmptyFiles() {
        when(ingestRunRepository.save(any(IngestRunEntity.class))).thenAnswer(inv -> {
            IngestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1);
            }
            return e;
        });
        when(matchResultRepository.findUnnotifiedByJobStatus(any())).thenReturn(List.of());
        when(matchResultRepository.search(null, null, JobStatus.ACTIVE)).thenReturn(List.of());

        IngestOrchestrator orchestrator = new IngestOrchestrator(List.of(), httpFetcher, companyRegistry,
            matchEngine, jobRecordRepository, matchResultRepository, ingestRunRepository, List.of());
        ReflectionTestUtils.setField(orchestrator, "dryRun", false);
        ReflectionTestUtils.setField(orchestrator, "exportDir", tempDir.toString());

        orchestrator.runOnce();

        Path jsonFile = tempDir.resolve("matches.json");
        Path csvFile = tempDir.resolve("matches.csv");
        assertThat(jsonFile).exists();
        assertThat(csvFile).exists();
        String json = readFile(jsonFile).trim();
        assertThat(json).startsWith("[").endsWith("]");
        assertThat(json.replaceAll("\\s", "")).isEqualTo("[]");
        assertThat(readFile(csvFile).lines().toList()).hasSize(1);
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
